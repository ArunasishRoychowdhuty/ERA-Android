import android.content.Context
import com.eraassistant.mobile.screen.PendingUiActionStore

fun main() {
    val context = Context()
    val store = PendingUiActionStore(context)
    val observationId = "a".repeat(64)

    check(store.approve("Search", PendingUiActionStore.ACTION_CLICK, reviewedObservationId = observationId))
    check(store.isPending())
    val pendingClick = store.pending() ?: error("approved click should be available")
    check(pendingClick.selectorText == "Search")
    check(pendingClick.action == PendingUiActionStore.ACTION_CLICK)
    check(pendingClick.reviewedObservationId == observationId)
    check(!store.approve("Different", PendingUiActionStore.ACTION_CLICK, reviewedObservationId = "bad"))
    check(!store.isPending())
    check(store.pending() == null)
    check(store.approve("Search", PendingUiActionStore.ACTION_CLICK, reviewedObservationId = observationId))

    val prefs = context.getSharedPreferences(PendingUiActionStore.PREFS, Context.MODE_PRIVATE)
    store.consume()
    check(!store.isPending())
    check(store.pending() == null)
    check(prefs.getString("payload", null) == null)
    check(prefs.getString("reviewed_observation_id", null) == null)

    check(!store.approve("Search", PendingUiActionStore.ACTION_CLICK, reviewedObservationId = "bad"))
    check(!store.approve("", PendingUiActionStore.ACTION_CLICK, reviewedObservationId = observationId))
    check(!store.approve("Search", "unknown", reviewedObservationId = observationId))
    check(!store.approve("Message", PendingUiActionStore.ACTION_TYPE_TEXT, reviewedObservationId = observationId))

    check(store.approve("Message", PendingUiActionStore.ACTION_TYPE_TEXT, "Hello", "com.example", reviewedObservationId = observationId))
    val pendingText = store.pending() ?: error("approved text action should be available")
    check(pendingText.payload == "Hello")
    check(pendingText.expectedPackage == "com.example")
    store.clearPending("Cancelled for test")
    check(!store.isPending())
    check(store.pending() == null)
    check(store.result() == "Cancelled for test")
    check(prefs.getString("payload", null) == null)
    check(prefs.getString("selector_text", null) == null)

    println("PASS: 25 pending-action assertions (snapshot binding, selector/payload validation, one-shot consumption and cancellation cleanup)")
}
