package io.github.zinzuzu.feedguard;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks at Firefox's windows through accessibility and reports three things:
 * the address in the address bar, whether a page is loading, and whether the
 * add-on's marker is on the page.
 *
 * Address bar ids, checked against Firefox's source in November 2026:
 * - "ADDRESSBAR_URL_BOX": the Compose toolbar's origin button. Its description
 *   is "{title} {url}. {hint}". Compose test tags show up as bare resource ids.
 * - "mozac_browser_toolbar_url_view": the older view-based toolbar.
 * The page itself is a GeckoView, which reports class android.webkit.WebView
 * and gives each element its DOM id as the resource id.
 */
final class PageInspector {
    private PageInspector() {}

    static final String WEBVIEW_CLASS = "android.webkit.WebView";
    static final String[] URL_IDS = {"ADDRESSBAR_URL", "ADDRESSBAR_URL_BOX", "mozac_browser_toolbar_url_view"};
    static final String[] PROGRESS_IDS = {"ADDRESSBAR_PROGRESSBAR", "mozac_browser_toolbar_progress"};
    /** Limits per check, so a huge page can't stall the guard. */
    static final int NATIVE_NODE_BUDGET = 1500;
    static final int WEB_NODE_BUDGET = 400;

    static final class Snapshot {
        boolean firefoxVisible;
        boolean urlBoxFound;
        String urlText;
        boolean loading;
        boolean webContent;
        String pageTitle;
        String markerLabel;
        int nodesVisited;
    }

    /**
     * @param windows the on-screen windows, from AccessibilityService.getWindows()
     * @param screenArea width x height of the screen, to skip picture-in-picture windows
     */
    static Snapshot inspect(List<AccessibilityWindowInfo> windows, long screenArea) {
        Snapshot s = new Snapshot();
        if (windows == null) return s;

        // Every Firefox window is searched (the page and the toolbar could sit in
        // different windows), but Firefox only counts as on screen if one of its
        // windows covers at least 30% of it. A picture-in-picture video alone doesn't.
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !Guard.FIREFOX.equals(str(root.getPackageName()))) continue;
            roots.add(root);
            w.getBoundsInScreen(r);
            if ((long) r.width() * r.height() * 10 >= screenArea * 3) s.firefoxVisible = true;
        }
        if (!s.firefoxVisible) return s;

        // Firefox's own interface, breadth-first. The page itself is set aside.
        List<AccessibilityNodeInfo> pages = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>(roots);
        int budget = NATIVE_NODE_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = queue.poll();
            s.nodesVisited++;
            if (WEBVIEW_CLASS.equals(str(n.getClassName()))) {
                pages.add(n);
                continue;
            }
            String id = n.getViewIdResourceName();
            if (id != null) {
                if (!s.urlBoxFound && matches(id, URL_IDS) && !n.isEditable()) {
                    s.urlBoxFound = true;
                    String t = str(n.getText());
                    s.urlText = t.isEmpty() ? str(n.getContentDescription()) : t;
                } else if (matches(id, PROGRESS_IDS) && n.isVisibleToUser()) {
                    s.loading = true;
                }
            }
            addChildren(queue, n);
        }

        // The page: the marker is the first element of the document, so this is quick.
        for (AccessibilityNodeInfo page : pages) {
            s.webContent = true;
            if (s.pageTitle == null) {
                String title = str(page.getText());
                if (!title.isEmpty()) s.pageTitle = title;
            }
            if (s.markerLabel == null) s.markerLabel = findMarker(page, s);
        }
        return s;
    }

    private static String findMarker(AccessibilityNodeInfo page, Snapshot s) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(page);
        int budget = WEB_NODE_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = queue.poll();
            s.nodesVisited++;
            String text = str(n.getText());
            String desc = str(n.getContentDescription());
            if (MarkerText.looksLikeMarker(text)) return text;
            if (MarkerText.looksLikeMarker(desc)) return desc;
            if (MarkerText.ELEMENT_ID.equals(n.getViewIdResourceName())) return text.isEmpty() ? desc : text;
            addChildren(queue, n);
        }
        return null;
    }

    private static void addChildren(ArrayDeque<AccessibilityNodeInfo> queue, AccessibilityNodeInfo n) {
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child != null) queue.add(child);
        }
    }

    static boolean matches(String resourceId, String[] ids) {
        for (String id : ids) {
            if (resourceId.equals(id) || resourceId.endsWith(":id/" + id)) return true;
        }
        return false;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
