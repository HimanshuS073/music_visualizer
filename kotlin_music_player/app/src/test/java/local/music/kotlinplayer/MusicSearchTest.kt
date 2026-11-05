package local.music.kotlinplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*

class MusicSearchTest {
    private fun withServer(handler: (Socket) -> Unit, test: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val workers = Executors.newSingleThreadExecutor()
        workers.execute {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (!input.readLine().isNullOrEmpty()) { }
                    handler(socket)
                }
            } catch (_: IOException) { } catch (_: InterruptedException) { }
        }
        try { test("http://127.0.0.1:${server.localPort}/image") }
        finally { server.close(); workers.shutdownNow() }
    }
    private fun headers(socket: Socket, value: String) {
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n$value\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
    }
    @Test fun blankSearchIsEmpty() { assertTrue(MusicSearch.search("  ").isEmpty()) }
    @Test fun cancelledSearchCannotStart() {
        val job = MusicSearch.Job().apply { cancel() }
        assertThrows(IOException::class.java) { MusicSearch.search("song", job) }
    }
    @Test fun cancelledTrackCannotResolve() {
        val job = MusicSearch.Job().apply { cancel() }
        val song = Song("test", "", "https://www.youtube.com/watch?v=ewr364UmWZ4", 0, "", "")
        assertThrows(IOException::class.java) { MusicSearch.resolve(song, job) }
    }
    @Test fun cancelledImageCannotStart() {
        val job = MusicSearch.Job().apply { cancel() }
        assertThrows(IOException::class.java) { MusicSearch.image("https://example.invalid/image", job) }
    }
    @Test fun imageBytesAreReturnedInMemory() {
        withServer({ socket ->
            headers(socket, "Content-Length: 3")
            socket.getOutputStream().write(byteArrayOf(1, 2, 3))
        }) { url -> assertArrayEquals(byteArrayOf(1, 2, 3), MusicSearch.image(url)) }
    }
    @Test fun oversizedContentLengthIsRejected() {
        withServer({ headers(it, "Content-Length: ${4 * 1024 * 1024 + 1}") }) { url ->
            assertTrue(assertThrows(IOException::class.java) { MusicSearch.image(url) }.message!!.contains("large"))
        }
    }
    @Test fun oversizedChunkedBodyIsRejected() {
        withServer({ socket ->
            headers(socket, "Transfer-Encoding: chunked")
            val bytes = ByteArray(4 * 1024 * 1024 + 1)
            socket.getOutputStream().apply {
                write((bytes.size.toString(16) + "\r\n").toByteArray())
                write(bytes)
                write("\r\n0\r\n\r\n".toByteArray())
            }
        }) { url ->
            assertTrue(assertThrows(IOException::class.java) { MusicSearch.image(url) }.message!!.contains("large"))
        }
    }
    @Test fun cancellationInterruptsActiveIo() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            withServer({ started.countDown(); finish.await(5, TimeUnit.SECONDS) }) { url ->
                val job = MusicSearch.Job()
                val task = worker.submit<ByteArray> { MusicSearch.image(url, job) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                job.cancel()
                val error = assertThrows(ExecutionException::class.java) { task.get(2, TimeUnit.SECONDS) }
                assertTrue(error.cause is IOException)
                finish.countDown()
            }
        } finally { finish.countDown(); worker.shutdownNow() }
    }
}
