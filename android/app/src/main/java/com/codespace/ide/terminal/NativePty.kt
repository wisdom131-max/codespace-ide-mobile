package com.codespace.ide.terminal

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

object NativePty {
    init {
        System.loadLibrary("ptynative")
    }

    external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processIdArray: IntArray,
        rows: Int,
        cols: Int,
    ): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    external fun waitFor(pid: Int): Int
}

class PtySession(
    shellPath: String,
    workingDir: String,
    args: Array<String>,
    envVars: Array<String>,
    rows: Int = 24,
    cols: Int = 80,
) {
    val pid: Int
    val fd: Int
    val inputStream: FileInputStream
    val outputStream: FileOutputStream

    init {
        val pidArray = IntArray(1)
        fd = NativePty.createSubprocess(shellPath, workingDir, args, envVars, pidArray, rows, cols)
        pid = pidArray[0]
        val fileDescriptor = FileDescriptor::class.java.getDeclaredConstructor().newInstance().apply {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(this, fd)
        }
        inputStream = FileInputStream(fileDescriptor)
        outputStream = FileOutputStream(fileDescriptor)
    }

    fun write(data: String) {
        try {
            outputStream.write(data.toByteArray())
            outputStream.flush()
        } catch (_: Exception) {}
    }

    fun resize(rows: Int, cols: Int) {
        NativePty.setWindowSize(fd, rows, cols)
    }

    fun destroy() {
        try {
            android.system.Os.kill(pid, 9)
        } catch (_: Exception) {}
    }
}
