package com.idm.test.runner.ui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

/** Opens the default browser where the desktop allows it; elsewhere the printed URL is all there is. */
final class BrowserOpener {

    private BrowserOpener() {
    }

    /** True when the browser was asked to open {@code uri}; false when this machine cannot. */
    static boolean open(URI uri) {
        try {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(uri);
            return true;
        } catch (Exception | LinkageError e) {
            // no browser, no desktop session, or no java.desktop module: the console shows the URL
            return false;
        }
    }
}
