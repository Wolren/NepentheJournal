package app.journal.util

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference
import app.journal.log.Log

/** What the modern picker did: a path, a cancel, or a platform that cannot run it. */
sealed class PickerOutcome {
    data class Picked(val path: String) : PickerOutcome()
    data object Cancelled : PickerOutcome()
    data object Unsupported : PickerOutcome()
}

/**
 * IFileOpenDialog, the Vista and later open dialog. AWT FileDialog still draws
 * the old common dialog, so this calls the COM object through its vtable.
 */
internal object WinFileDialog {
    private const val CLSCTX_ALL = 0x17
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000
    private const val SIGDN_FILESYSPATH = 0x80058000L

    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_FILE_TYPES = 4
    private const val SET_OPTIONS = 9
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val ITEM_GET_DISPLAY_NAME = 5

    private val clsidFileOpenDialog = Guid.GUID("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")
    private val iidFileOpenDialog = Guid.GUID("D57C7288-D4AD-4768-BE02-9D969532D960")

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Creates the COM object and frees it again, to prove the platform supports it. */
    fun available(): Boolean {
        if (!isWindows()) return false
        val dialog = create() ?: return false
        release(dialog)
        return true
    }

    fun pick(title: String, extensions: List<String>): PickerOutcome {
        if (!isWindows()) return PickerOutcome.Unsupported
        Ole32.INSTANCE.OleInitialize(null)
        val dialog = create() ?: return PickerOutcome.Unsupported
        var item: Pointer? = null
        return try {
            configure(dialog, title, extensions)

            val showResult = callInt(dialog, SHOW, null)
            if (showResult != 0) return PickerOutcome.Cancelled

            val itemOut = Memory(Native.POINTER_SIZE.toLong())
            if (callInt(dialog, GET_RESULT, itemOut) != 0) return PickerOutcome.Cancelled
            item = itemOut.getPointer(0) ?: return PickerOutcome.Cancelled

            val pathOut = Memory(Native.POINTER_SIZE.toLong())
            if (callInt(item, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH.toInt(), pathOut) != 0) {
                return PickerOutcome.Cancelled
            }
            val pathPtr = pathOut.getPointer(0) ?: return PickerOutcome.Cancelled
            val path = pathPtr.getWideString(0)
            Ole32.INSTANCE.CoTaskMemFree(pathPtr)
            PickerOutcome.Picked(path)
        } catch (e: Throwable) {
            Log.withTag("Picker").w(e) { "modern dialog failed" }
            PickerOutcome.Unsupported
        } finally {
            item?.let { release(it) }
            release(dialog)
        }
    }

    /** Sets the title, the image filter and the file options on a created dialog. */
    private fun configure(dialog: Pointer, title: String, extensions: List<String>) {
        val displayTitle = wide(title)
        callInt(dialog, SET_TITLE, displayTitle)
        val filterName = wide("Images")
        val filterSpec = wide(extensions.joinToString(";") { "*.$it" })
        val filters = Memory((Native.POINTER_SIZE * 2).toLong())
        filters.setPointer(0, filterName)
        filters.setPointer(Native.POINTER_SIZE.toLong(), filterSpec)
        callInt(dialog, SET_FILE_TYPES, 1, filters)
        callInt(dialog, SET_OPTIONS, FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_FILEMUSTEXIST)
    }

    /** Creates, configures and frees a dialog without showing it, so tests can prove the bindings. */
    internal fun selfTest(): Boolean {
        if (!isWindows()) return false
        val dialog = create() ?: return false
        return try {
            configure(dialog, "Self test", listOf("png", "jpg"))
            true
        } catch (e: Throwable) {
            Log.withTag("Picker").w(e) { "picker self test failed" }
            false
        } finally {
            release(dialog)
        }
    }

    private fun create(): Pointer? {
        return try {
            // COM must run in an initialized apartment; S_FALSE and RPC_E_CHANGED_MODE are both usable.
            Ole32.INSTANCE.OleInitialize(null)
            val out = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                clsidFileOpenDialog, null, CLSCTX_ALL, iidFileOpenDialog, out
            )
            if (!COMUtils.SUCCEEDED(hr)) {
                Log.withTag("Picker").w { "CoCreateInstance failed: $hr" }
                null
            } else out.value
        } catch (e: Throwable) {
            Log.withTag("Picker").w(e) { "CoCreateInstance threw" }
            null
        }
    }

    private fun wide(text: String): Memory {
        val memory = Memory(text.length * 2L + 2L)
        memory.setWideString(0, text)
        return memory
    }

    private fun release(target: Pointer) {
        try {
            callInt(target, RELEASE)
        } catch (_: Throwable) {
            // A release that fails must not mask the picker result.
        }
    }

    private fun callInt(instance: Pointer, index: Int, vararg args: Any?): Int =
        functionAt(instance, index).invokeInt(arrayOf(instance, *args))

    private fun functionAt(instance: Pointer, index: Int): Function {
        val vtable = instance.getPointer(0)
        // COM methods use the stdcall convention; on x64 JNA ignores the flag.
        return Function.getFunction(vtable.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    }
}
