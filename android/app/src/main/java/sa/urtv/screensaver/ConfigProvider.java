package sa.yourtv.v2.screensaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only: hands the launcher (same signing key, see READ_CONFIG in the manifest) what its home-screen
 * card needs: the page settings as a URL fragment and which card to show ("none" = off), and (content://…/page)
 * the screen page as this app last updated it (Updater), so the home screen shows the same page.
 */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Prefs p = new Prefs(getContext());
        MatrixCursor c = new MatrixCursor(new String[] {"fragment", "home_panel"});
        c.addRow(new Object[] {p.fragment(), p.homePanel()});
        return c;
    }

    @Override
    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        java.io.File f = Updater.pageFile(getContext());
        if (!"/page".equals(uri.getPath()) || !f.isFile()) throw new java.io.FileNotFoundException("page");
        return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
