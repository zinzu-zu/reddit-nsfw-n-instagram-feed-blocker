package io.github.zinzuzu.feedguard;

/**
 * Reads and edits Android's list of switched-on accessibility services (the
 * secure setting enabled_accessibility_services): component names such as
 * "io.github.zinzuzu.feedguard/.GuardService", separated by colons.
 *
 * Pure Java, so it can be tested off the phone.
 */
final class ServiceList {
    private ServiceList() {}

    /** Whether the list holds the service, written in short or long form. */
    static boolean contains(String list, String service) {
        String[] want = split(service);
        if (list == null || want == null) return false;
        for (String item : list.split(":")) {
            String[] got = split(item.trim());
            if (got != null && got[0].equals(want[0]) && got[1].equals(want[1])) return true;
        }
        return false;
    }

    /** The list with the service added at the end, or null if it's already there. */
    static String withService(String list, String service) {
        if (contains(list, service)) return null;
        StringBuilder b = new StringBuilder();
        if (list != null) {
            for (String item : list.split(":")) {
                String t = item.trim();
                if (t.isEmpty()) continue;
                if (b.length() > 0) b.append(':');
                b.append(t);
            }
        }
        if (b.length() > 0) b.append(':');
        return b.append(service).toString();
    }

    /**
     * Splits the list in two: the services of the given apps, and all the
     * others. Items are kept exactly as written, so they can be put back as
     * they were. Other apps' services are never touched.
     *
     * @return {the list without those apps' services, those services}; each
     *         colon-separated, "" when empty
     */
    static String[] without(String list, java.util.Set<String> packages) {
        StringBuilder kept = new StringBuilder();
        StringBuilder taken = new StringBuilder();
        if (list != null) {
            for (String item : list.split(":")) {
                String t = item.trim();
                if (t.isEmpty()) continue;
                String pkg = packageOf(t);
                StringBuilder to = pkg != null && packages != null && packages.contains(pkg) ? taken : kept;
                if (to.length() > 0) to.append(':');
                to.append(t);
            }
        }
        return new String[] {kept.toString(), taken.toString()};
    }

    /** The list with each item of items (colon-separated) added at the end, unless it's already there. */
    static String withAll(String list, String items) {
        String out = list == null ? "" : list;
        if (items == null) return out;
        for (String item : items.split(":")) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            String next = withService(out, t);
            if (next != null) out = next;
        }
        return out;
    }

    /** The package of a component name such as "com.example/.Ime", or null. */
    static String packageOf(String component) {
        String[] parts = split(component == null ? null : component.trim());
        return parts == null ? null : parts[0];
    }

    /** "pkg/cls" or "pkg/.cls" -> {pkg, full class name}; null if it isn't a component name. */
    static String[] split(String flat) {
        if (flat == null) return null;
        int slash = flat.indexOf('/');
        if (slash <= 0 || slash == flat.length() - 1) return null;
        String pkg = flat.substring(0, slash);
        String cls = flat.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return new String[] {pkg, cls};
    }
}
