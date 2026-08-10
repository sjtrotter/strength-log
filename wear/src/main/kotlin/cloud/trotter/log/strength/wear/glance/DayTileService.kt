package cloud.trotter.log.strength.wear.glance

import android.util.Log
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import cloud.trotter.log.strength.wear.MainActivity
import cloud.trotter.log.strength.wear.data.SnapshotItem

/**
 * The day in the tile carousel (glance-surfaces brief §3): a mini dial, one glance,
 * one tap into the app.
 *
 * The timeline is a single entry with no freshness interval — nothing here is
 * time-dependent, so there is nothing for the system to re-request on a clock. The
 * tile is redrawn when the carousel opens it and when [GlanceUpdateService] pushes
 * an update after a new snapshot lands. No polling, no alarms.
 */
class DayTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = SuspendToFutureAdapter.launchFuture {
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    dayTileLayout(DayGlance.of(readSnapshot()), glanceCopy(), openApp()),
                ),
            )
            .build()
    }

    /** The layout draws itself — no images, no fonts, nothing to hand over. */
    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private suspend fun readSnapshot() = try {
        SnapshotItem.latest(Wearable.getDataClient(this))
    } catch (e: Exception) {
        // Rendering the empty state beats failing the request: a tile that throws
        // just leaves the last frame frozen in the carousel with no explanation.
        Log.w(TAG, "reading the snapshot for the tile failed", e)
        null
    }

    private fun openApp() = ModifiersBuilders.Clickable.Builder()
        .setId(CLICK_OPEN)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()

    private fun glanceCopy() = dayGlanceCopy(this)

    private companion object {

        const val TAG = "DayTile"

        /** Bump only when the tile starts shipping resources; it has none today. */
        const val RESOURCES_VERSION = "1"

        const val CLICK_OPEN = "open"
    }
}
