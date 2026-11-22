package com.gathin.tvhome;

/**
 * The plain rules behind the update check (Updater): when to ask GitHub again, whether a release tag is newer
 * than this build, and how to read the release's checksum and notes. No android.* here, so it is tested on the JVM.
 */
final class UpdateCheck {
    /** Ask GitHub at most once a day. */
    static final long INTERVAL_MS = 24L * 60 * 60 * 1000;
    /** The release notes shown in the prompt are cut to this many characters. */
    static final int NOTES_MAX = 240;

    private UpdateCheck() {}

    /** A day has passed since the last check, or the clock went back (a TV without time sync), or never checked. */
    static boolean due(long lastCheckMs, long nowMs) {
        return lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= INTERVAL_MS;
    }

    /** "v4.10" is newer than "4.9"; equal, older or unreadable tags are not newer. */
    static boolean isNewer(String tag, String versionName) {
        int[] release = parse(tag);
        int[] installed = parse(versionName);
        if (release == null || installed == null) return false;
        int n = Math.max(release.length, installed.length);
        for (int i = 0; i < n; i++) {
            int a = i < release.length ? release[i] : 0;
            int b = i < installed.length ? installed[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /** "4.6.2" or "v4.6.2" as numbers; null for anything else ("nightly", "4.6-beta", ""). */
    static int[] parse(String version) {
        if (version == null) return null;
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        if (v.isEmpty()) return null;
        String[] parts = v.split("\\.", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 6) return null;
            for (int c = 0; c < p.length(); c++) if (p.charAt(c) < '0' || p.charAt(c) > '9') return null;
            out[i] = Integer.parseInt(p);
        }
        return out;
    }

    /** GitHub's asset digest ("sha256:" and 64 hex digits) as lowercase hex, or null when it is missing or another kind. */
    static String sha256Hex(String digest) {
        if (digest == null || !digest.startsWith("sha256:")) return null;
        String hex = digest.substring("sha256:".length()).trim().toLowerCase(java.util.Locale.ROOT);
        if (hex.length() != 64) return null;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return null;
        }
        return hex;
    }

    /** The release body as a few plain lines for the prompt: no Markdown headings or bullets, cut at NOTES_MAX. */
    static String notes(String body) {
        if (body == null) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            while (line.startsWith("#") || line.startsWith("-") || line.startsWith("*") || line.startsWith(">")) {
                line = line.substring(1).trim();
            }
            line = line.replace("**", "").replace("`", "");
            if (line.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
            if (out.length() >= NOTES_MAX) break;
        }
        if (out.length() <= NOTES_MAX) return out.toString();
        String cut = out.substring(0, NOTES_MAX - 1).trim();
        while (!cut.isEmpty() && ".,;:!?".indexOf(cut.charAt(cut.length() - 1)) >= 0) cut = cut.substring(0, cut.length() - 1);
        return cut + "…";
    }
}
