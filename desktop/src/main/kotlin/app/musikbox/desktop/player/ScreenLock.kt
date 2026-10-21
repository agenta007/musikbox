@file:Suppress("FunctionName")

package app.musikbox.desktop.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal

@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface FreedesktopScreenSaver : DBusInterface {
    class ActiveChanged(path: String, val active: Boolean) : DBusSignal(path, active)
}

@DBusInterfaceName("org.gnome.ScreenSaver")
interface GnomeScreenSaver : DBusInterface {
    class ActiveChanged(path: String, val active: Boolean) : DBusSignal(path, active)
}

/**
 * Follows the session's screen locker (KDE/freedesktop and GNOME) over D-Bus.
 *
 * Skiko renders every frame on the UI thread with a vsync'd swap followed by glFinish, even for
 * a window nobody can see. While the screen is locked or the monitor is asleep the compositor
 * stops presenting, so each frame can stall the UI thread; continuous animations stop while
 * [locked] is true.
 */
class ScreenLock {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var conn: DBusConnection? = null

    fun start(): Result<Unit> = runCatching {
        val c = DBusConnectionBuilder.forSessionBus().build()
        c.addSigHandler(FreedesktopScreenSaver.ActiveChanged::class.java) { _locked.value = it.active }
        c.addSigHandler(GnomeScreenSaver.ActiveChanged::class.java) { _locked.value = it.active }
        conn = c
    }

    fun stop() {
        runCatching { conn?.close() }
        conn = null
    }
}
