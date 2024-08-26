package com.programmersbox.scrcpyviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lordcodes.turtle.shellRun
import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.interactor.StartAdbInteractor
import com.malinskiy.adam.request.device.AsyncDeviceMonitorRequest
import com.malinskiy.adam.request.device.Device
import com.malinskiy.adam.request.device.DeviceState
import com.malinskiy.adam.request.device.ListDevicesRequest
import com.malinskiy.adam.request.prop.GetPropRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import kotlin.getValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Preview
fun App(
    adbRepo: AdbRepo,
) {
    val devices by adbRepo.watchConnectedDevice().collectAsState(emptyList())
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Scrcpy Viewer") }) },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                items(devices) {
                    OutlinedCard(
                        onClick = { adbRepo.openScrcpy(it) },
                        modifier = Modifier.animateItem()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .padding(4.dp)
                                .fillMaxSize()
                        ) {
                            Text(it.name)
                            Text(it.model)
                            Text(it.manufacturer)
                            Text(it.api)
                            Text(it.sdk)
                            Text(it.device.state.name)
                        }
                    }
                }
            }
        }
    }
}

class AdbRepo {

    companion object {
        const val PATH_PACKAGE_PREFIX = "package:"
        private const val DETAIL_UNKNOWN = "Unknown"
        private const val ADB_ZIP_ENTRY_NAME = "platform-tools/adb"
        private const val ADB_ZIP_ENTRY_NAME_WINDOWS = "platform-tools/adb.exe"
        private const val ADB_ZIP_ENTRY_NAME_WINDOWS_API_DLL = "platform-tools/AdbWinApi.dll"
        private const val ADB_ZIP_ENTRY_NAME_WINDOWS_API_USB_DLL = "platform-tools/AdbWinUsbApi.dll"

        private val ADB_ROOT_DIR = "${System.getProperty("user.home")}${File.separator}.stackzy"

        // platform-tools url map
        private val platformToolsUrlMap by lazy {
            mapOf(
                OSType.Linux to "https://dl.google.com/android/repository/platform-tools-latest-linux.zip",
                OSType.Windows to "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
                OSType.MacOS to "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip",
            )
        }
    }

    private val adb by lazy {
        AndroidDebugBridgeClientFactory()
            .build()
    }

    private val startAdbInteractor by lazy {
        StartAdbInteractor()
    }

    private val adbZipEntryName by lazy {
        if (OsCheck.operatingSystemType == OSType.Windows) {
            ADB_ZIP_ENTRY_NAME_WINDOWS
        } else {
            ADB_ZIP_ENTRY_NAME
        }
    }

    private val adbFile by lazy {
        // only the filename (platform-tools/'adb/adb.exe')
        val fileName = adbZipEntryName.split("/").last()
        File("${ADB_ROOT_DIR}${File.separator}$fileName").also {
            it.parentFile.let { parentDir ->
                if (parentDir.exists().not()) {
                    parentDir.mkdirs()
                }
            }
        }
    }

    fun watchConnectedDevice(): Flow<List<AndroidDevice>> {
        return flow {
            val isStarted = isAdbStarted()

            if (isStarted) {
                val deviceEventsChannel = adb.execute(
                    request = AsyncDeviceMonitorRequest(),
                    scope = GlobalScope
                )

                adb.execute(request = ListDevicesRequest())

                for (currentDeviceList in deviceEventsChannel) {
                    val deviceList = currentDeviceList
                        .filter { it.state == DeviceState.DEVICE }
                        .map { device ->
                            val props = adb.execute(
                                request = GetPropRequest(),
                                serial = device.serial
                            )

                            val deviceProductManufacturer =
                                props["ro.product.manufacturer"]?.singleLine() ?: DETAIL_UNKNOWN
                            val deviceProductName = props["ro.product.name"]?.singleLine() ?: DETAIL_UNKNOWN
                            val deviceProductModel = props["ro.product.model"]?.singleLine() ?: DETAIL_UNKNOWN
                            val deviceApi = "Api: " + props["ro.build.version.release"]?.singleLine()
                            val deviceSdk = "Sdk: " + props["ro.build.version.sdk"]?.singleLine()

                            AndroidDevice(
                                manufacturer = deviceProductManufacturer,
                                name = deviceProductName,
                                model = deviceProductModel,
                                device = device,
                                api = deviceApi,
                                sdk = deviceSdk,
                            )
                        }

                    // Finally emitting result
                    emit(deviceList)
                }
            } else {
                throw IOException("Failed to start adb")
            }
        }
    }

    suspend fun isAdbStarted() = if (adbFile.exists()) {
        startAdbInteractor.execute(adbFile)
    } else {
        startAdbInteractor.execute()
    }

    val logsFromScrcpy = mutableStateMapOf<AndroidDevice, List<String>>()

    fun openScrcpy(device: AndroidDevice) {
        runCatching {
            logsFromScrcpy[device] = emptyList()
            GlobalScope.launch {
                //Runtime.getRuntime().exec("scrcpy -s $serial")
                val d = shellRun {
                    commandSequence("scrcpy", listOf("-s", device.device.serial))
                        .forEach {
                            println(it)
                            logsFromScrcpy[device] = logsFromScrcpy[device].orEmpty() + it
                        }
                    ""
                }
                println(d)
                /*val shell = Shell.SH
                shell.addOnStdoutLineListener(
                    object : Shell.OnLineListener {
                        override fun onLine(line: String) {
                            logsFromScrcpy[device] = logsFromScrcpy[device].orEmpty() + line
                        }
                    }
                )
                val result = shell.run("scrcpy -s ${device.device.serial}")*/
            }
        }.onFailure { it.printStackTrace() }
    }

    fun runCommand(cmd: String) {
        val runtime = Runtime.getRuntime()
        val process = runtime.exec(cmd)
        val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
        val line = bufferedReader.readLine()
        while (line != null) {
            print(line)
        }
        val exitValue = process.waitFor()
        println("Exited with error code : $exitValue")
    }
}

private fun String.singleLine(): String = replace("\n", "")

data class AndroidDevice(
    val manufacturer: String,
    val name: String,
    val model: String,
    val device: Device,
    val api: String,
    val sdk: String,
) {
    val deviceInfo = "$manufacturer $name $model Sdk: $sdk Api: $api"
}

/**
 * Types of Operating Systems
 */
enum class OSType {
    Windows, MacOS, Linux, Other
}

object OsCheck {
    /**
     * detect the operating system from the os.name System property a
     *
     * @returns - the operating system detected
     */
    val operatingSystemType: OSType by lazy {

        val os = System
            .getProperty("os.name", "generic")
            .lowercase(Locale.ENGLISH)

        if (os.indexOf("mac") >= 0 || os.indexOf("darwin") >= 0) {
            OSType.MacOS
        } else if (os.indexOf("win") >= 0) {
            OSType.Windows
        } else if (os.indexOf("nux") >= 0) {
            OSType.Linux
        } else {
            OSType.Other
        }
    }
}