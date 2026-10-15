package io.github.haydengriffin.prism;

import android.os.Handler;
import java.util.List;

/** What each screen needs from the activity: the page, a read worker and a command worker, saved setup, navigation. */
interface Host {
    enum Page { LAUNCHER, APPS, FAVOURITES, QUICK_SETTINGS, SETUP, CONNECTION, CHOOSE_TV, SHIELD_INPUT, PERMISSION, REMOTES, MONITORING }

    Screen screen();
    Handler main();
    IconCache icons();

    /** Runs off the main thread; results are dropped when the page has changed since (see request()). */
    boolean work(Runnable task);
    /** Runs a command for the TV (launch, LG Home, a setting) off the main thread, never behind a read. */
    boolean command(Runnable task);
    int request();
    boolean current(int request);
    String error(Exception e);

    Credentials credentials();
    void credentials(Credentials next);
    TvProfile profile();
    void profile(TvProfile next);
    HaHttp api();

    Apps.Favourites favourites();
    void saveFavourites();
    /** The last app catalog seen, for drawing at once; refreshed in the background. */
    List<Apps.App> catalog();
    void catalog(List<Apps.App> apps);
    /** The Continue tile's LG app id as last shown, or null; kept so a cold start draws the same rows. */
    String resumeApp();
    void resumeApp(String id);

    void show(Page page);
    /** Opens an app on the LG; the remote follows it there. */
    void launch(Apps.App app);
    void openLgHome();
}
