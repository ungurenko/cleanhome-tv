package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-click updates from a public GitHub repository (config.xml update_repo). Once a day it asks for the latest
 * release and remembers a newer one as an offer; Home shows the offer, and "Update" downloads the APK, checks its
 * size, SHA-256, package name and version, and hands it to the system installer. Android itself refuses an APK
 * signed with another key, so a swapped file can never replace the launcher. Network only over https to GitHub.
 */
final class Updater {
    static final String PREFS = "updates";
    private static final String LAST_CHECK = "last_check";
    private static final String TAG = "tag", URL_KEY = "url", SIZE = "size", SHA = "sha256", NOTES = "notes";
    private static final String SKIPPED = "skipped_tag";
    private static final String DIR = "updates", APK = "CleanHome.apk";
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final int TIMEOUT_MS = 10_000;
    /** After a failed request (no internet, GitHub down) wait this long before asking again in this process. */
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("api.github.com", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com"));

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "updates");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Releases on GitHub are built for this package; builds with another applicationId never check. */
    static final String RELEASE_PACKAGE = "com.gathin.tvhome";
    private static volatile long failedAt;
    private static volatile boolean busy;
    /** Main thread only: one download per process, every caller waits for it; the checked file is kept for reuse. */
    private static final java.util.List<Downloaded> waiting = new java.util.ArrayList<>();
    private static File ready;
    private static String readyTag;

    /** A newer release found by the last check. */
    static final class Offer {
        final String tag, url, sha256, notes;
        final long size;

        Offer(String tag, String url, long size, String sha256, String notes) {
            this.tag = tag;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
            this.notes = notes;
        }

        /** "4.8" for the prompt, from the tag "v4.8". */
        String version() {
            return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        }
    }

    interface Downloaded {
        /** On the main thread: the checked APK, or null with a message id for the user. */
        void done(File apk, int error);
    }

    private Updater() {}

    /** "owner/name" only, so a theme value can never point the request anywhere but the GitHub API. */
    static boolean validRepo(String repo) {
        return repo != null && repo.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean checkDue(Context ctx) {
        if (busy || System.currentTimeMillis() - failedAt < RETRY_MS) return false;
        return UpdateCheck.due(prefs(ctx).getLong(LAST_CHECK, 0), System.currentTimeMillis());
    }

    /** The remembered newer release, or null (none, or this build is already as new). */
    static Offer offer(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String tag = p.getString(TAG, null);
        String url = p.getString(URL_KEY, null);
        if (tag == null || url == null || !UpdateCheck.isNewer(tag, installedVersion(ctx))) return null;
        return new Offer(tag, url, p.getLong(SIZE, 0), p.getString(SHA, null), p.getString(NOTES, ""));
    }

    /** The user pressed Later on this release: Home stops asking until a newer one comes out. */
    static boolean skipped(Context ctx, Offer offer) {
        return offer.tag.equals(prefs(ctx).getString(SKIPPED, null));
    }

    static void skip(Context ctx, Offer offer) {
        prefs(ctx).edit().putString(SKIPPED, offer.tag).apply();
    }

    static String installedVersion(Context ctx) {
        try {
            String name = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static long installedCode(Context ctx) {
        try {
            return versionCode(ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** Asks GitHub for the latest release in the background; onFound runs on the main thread when it is newer. */
    static void check(Context context, String repo, Runnable onFound) {
        if (!validRepo(repo) || busy) return;
        Context ctx = context.getApplicationContext();
        busy = true;
        IO.execute(() -> {
            boolean found = false;
            try {
                JSONObject release = new JSONObject(new String(
                        get("https://api.github.com/repos/" + repo + "/releases/latest"), "UTF-8"));
                SharedPreferences.Editor editor = prefs(ctx).edit().putLong(LAST_CHECK, System.currentTimeMillis());
                String tag = release.optString("tag_name", "");
                JSONObject apk = release.optBoolean("draft") || release.optBoolean("prerelease") ? null : apkAsset(release);
                if (apk != null && UpdateCheck.isNewer(tag, installedVersion(ctx))) {
                    editor.putString(TAG, tag)
                            .putString(URL_KEY, apk.optString("browser_download_url", ""))
                            .putLong(SIZE, apk.optLong("size", 0))
                            .putString(SHA, UpdateCheck.sha256Hex(apk.optString("digest", null)))
                            .putString(NOTES, UpdateCheck.notes(release.optString("body", "")));
                    found = true;
                } else {
                    // No newer release any more (pulled or made a draft): forget the old offer.
                    editor.remove(TAG).remove(URL_KEY).remove(SIZE).remove(SHA).remove(NOTES);
                }
                editor.apply();
                failedAt = 0;
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
            } finally {
                busy = false;
            }
            if (found && onFound != null) MAIN.post(onFound);
        });
    }

    private static JSONObject apkAsset(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) return asset;
        }
        return null;
    }

    /**
     * Downloads and checks the offer's APK in the background; call on the main thread. A file already checked for
     * this release is handed back at once, and a call while one download runs waits for that same download.
     */
    static void download(Context context, Offer offer, Downloaded done) {
        if (ready != null && offer.tag.equals(readyTag) && ready.isFile()) {
            done.done(ready, 0);
            return;
        }
        waiting.add(done);
        if (waiting.size() > 1) return;
        Context ctx = context.getApplicationContext();
        IO.execute(() -> {
            File apk = null;
            int error = R.string.update_failed_download;
            try {
                apk = fetchApk(ctx, offer);
                error = 0;
            } catch (SecurityException e) {
                error = R.string.update_failed_check;
            } catch (Exception e) {
                error = R.string.update_failed_download;
            }
            File result = apk;
            int message = error;
            MAIN.post(() -> {
                ready = result;
                readyTag = result == null ? null : offer.tag;
                java.util.List<Downloaded> callers = new java.util.ArrayList<>(waiting);
                waiting.clear();
                for (Downloaded caller : callers) caller.done(result, message);
            });
        });
    }

    /**
     * Without the "install unknown apps" right, opens its screen and says so, and returns true: the caller then
     * waits for the next press instead of downloading a file the installer could not take yet.
     */
    static boolean askInstallPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) return false;
        Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        if (!start(activity, allow)) start(activity, new Intent(Settings.ACTION_SECURITY_SETTINGS));
        Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
        return true;
    }

    private static File fetchApk(Context ctx, Offer offer) throws Exception {
        if (offer.size <= 0 || offer.size > MAX_APK_BYTES) throw new SecurityException("size");
        File dir = new File(ctx.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("dir");
        File part = new File(dir, APK + ".part");
        File apk = new File(dir, APK);
        apk.delete();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long total = 0;
        HttpURLConnection connection = open(offer.url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > offer.size) throw new SecurityException("longer than announced");
                sha.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        try {
            if (total != offer.size) throw new SecurityException("size");
            if (offer.sha256 != null && !offer.sha256.equals(hex(sha.digest()))) throw new SecurityException("sha256");
            PackageInfo info = ctx.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(), 0);
            if (info == null || !ctx.getPackageName().equals(info.packageName)) throw new SecurityException("package");
            if (versionCode(info) <= installedCode(ctx)) throw new SecurityException("not newer");
            if (!part.renameTo(apk)) throw new IOException("rename");
            return apk;
        } catch (Exception e) {
            part.delete();
            throw e;
        }
    }

    /** Hands the checked APK to the system installer, first asking for the "install unknown apps" right once. */
    static void install(Activity activity, File apk) {
        if (askInstallPermission(activity)) return;
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!start(activity, install)) Toast.makeText(activity, R.string.not_available, Toast.LENGTH_LONG).show();
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 1024 * 1024) throw new IOException("answer too long");
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** An https connection to an allowed GitHub host, following up to five redirects, each checked again. */
    private static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            if (!"https".equals(url.getProtocol()) || !HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))) {
                throw new SecurityException("host " + url.getHost());
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "CleanHome-TV");
            connection.setRequestProperty("Accept", "api.github.com".equals(url.getHost())
                    ? "application/vnd.github+json" : "application/octet-stream");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("redirect without location");
                url = new URL(url, location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            return connection;
        }
        throw new IOException("too many redirects");
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}
