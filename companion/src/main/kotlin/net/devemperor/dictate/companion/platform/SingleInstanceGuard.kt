package net.devemperor.dictate.companion.platform

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * Guarantees a single running companion, and lets a second launch hand focus back to the first.
 *
 * **Why this exists.** The companion is the *only* server in the system and it binds a fixed port
 * (8756, ADR-0017). A second process therefore cannot serve — it can only lose the race for the port.
 * Worse, before this guard a second launch did not fail loudly: it started its own [CompanionServer],
 * the bind eventually failed on a background thread, the boot future never completed, and the window
 * sat in its loading spinner forever (observed 2026-07-15, two live instances). The honest behaviour
 * for "already running" is to *not start a second app at all* — surface the one that is already there.
 *
 * **How.** An OS advisory lock ([FileLock]) on a file in the data directory is the instance token: the
 * OS releases it automatically when the holder dies, so "lock held" reliably means "another live
 * instance", with no stale-PID files to reap. The primary also opens a loopback [ServerSocket] on an
 * ephemeral port and publishes that port next to the lock; a secondary reads it, connects, sends
 * [SHOW] and exits — that is the whole "show the existing window" signal. The socket is bound to
 * `127.0.0.1`, so nothing off the machine can reach it.
 *
 * **Layering.** This is deliberately free of Compose/AWT: [onShowRequested] hands the caller the raw
 * "someone asked us to surface" event on the listener thread, and *the caller* marshals it onto the
 * UI thread. That keeps the signal protocol unit-testable without a display (see
 * `SingleInstanceGuardTest`).
 */
class SingleInstanceGuard private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val serverSocket: ServerSocket,
    private val portFile: Path,
) {

    @Volatile
    private var showHandler: (() -> Unit)? = null

    /**
     * Register what to do when another launch asks this instance to surface. The handler runs on the
     * signal-listener thread; a UI caller is responsible for hopping to its own thread (e.g.
     * `EventQueue.invokeLater`).
     */
    fun onShowRequested(handler: () -> Unit) {
        showHandler = handler
    }

    private fun startListening() {
        thread(isDaemon = true, name = "single-instance-signal") {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { socket ->
                        val line = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        if (line?.trim() == SHOW) showHandler?.invoke()
                    }
                } catch (e: IOException) {
                    if (serverSocket.isClosed) break // normal shutdown via release()
                    // A single dropped connection must not kill the listener; keep accepting.
                }
            }
        }
    }

    /** Release the lock and stop listening. The OS would do this on exit anyway; this is the tidy path. */
    fun release() {
        runCatching { serverSocket.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(portFile) }
    }

    /** The outcome of [acquire]: either we are the one true instance, or one is already running. */
    sealed interface Acquisition {

        /** We hold the lock. Keep [guard] alive for the process lifetime and [SingleInstanceGuard.release] on quit. */
        data class Primary(val guard: SingleInstanceGuard) : Acquisition

        /** Another instance holds the lock. [requestShow] asks it to surface its window. */
        class AlreadyRunning internal constructor(private val portFile: Path) : Acquisition {
            /** Tell the running instance to show its window. False if it could not be reached (mid-start, stale). */
            fun requestShow(): Boolean = signalPrimary(portFile)
        }
    }

    companion object {

        private const val SHOW = "SHOW"
        private const val LOCK_FILE = "companion.lock"
        private const val PORT_FILE = "companion.signal-port"

        /**
         * Try to become the single instance in [dataDir]. Returns [Acquisition.Primary] if we won the
         * lock (and are now listening for show-requests), or [Acquisition.AlreadyRunning] if another
         * process already holds it.
         */
        fun acquire(dataDir: Path): Acquisition {
            Files.createDirectories(dataDir)
            val portFile = dataDir.resolve(PORT_FILE)
            val channel = FileChannel.open(
                dataDir.resolve(LOCK_FILE),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )

            // tryLock() returns null when another *process* holds the lock; it throws
            // OverlappingFileLockException when the *same JVM* already holds it (the shape a unit test
            // creates). Both mean the same thing here: someone is already running.
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }

            if (lock == null) {
                runCatching { channel.close() }
                return Acquisition.AlreadyRunning(portFile)
            }

            val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
            publishPort(portFile, serverSocket.localPort)
            return Acquisition.Primary(SingleInstanceGuard(channel, lock, serverSocket, portFile).also { it.startListening() })
        }

        /** Atomic publish so a secondary never reads a half-written port. */
        private fun publishPort(portFile: Path, port: Int) {
            val tmp = portFile.resolveSibling("$PORT_FILE.tmp")
            Files.writeString(tmp, port.toString(), StandardCharsets.UTF_8)
            runCatching { Files.move(tmp, portFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { Files.move(tmp, portFile, StandardCopyOption.REPLACE_EXISTING) }
        }

        /** Connect to the primary's loopback signal socket and ask it to show. Internal for tests. */
        internal fun signalPrimary(portFile: Path): Boolean = try {
            val port = Files.readString(portFile, StandardCharsets.UTF_8).trim().toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().apply {
                    write("$SHOW\n".toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
            }
            true
        } catch (e: Exception) {
            // The primary may be mid-start (port not published yet) or the file may be stale. Either
            // way the secondary still exits — never start a second UI — so a false here is not fatal.
            false
        }

        private const val BACKLOG = 4
    }
}
