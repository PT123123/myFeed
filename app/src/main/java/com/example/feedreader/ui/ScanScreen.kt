package com.example.feedreader.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.feedreader.data.DeepLink
import com.example.feedreader.data.ScanRules
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 应用内扫一扫。
 *
 * 只负责「把镜头里的二维码变成一段文本」，认不认得、要做什么全在 [ScanRules] 里 ——
 * 那部分有单测，这里没有。
 *
 * **扫到就停**：一次识别成功立刻拆掉分析器，否则回调会在用户看清界面之前再命中三次。
 * 拒掉的码（畸形 id、回环地址）不退出，把原因写在原地让人接着扫 —— 二维码墙上一个码
 * 扫错是常事，退出重来比解释「你刚才扫的是 subscribe.html」友好。
 */
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onScanned: (DeepLink) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var denied by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("把订阅二维码放进框里") }
    var stopped by remember { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        denied = !allowed
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (granted && !stopped) {
                CameraPreview(
                    onText = { raw ->
                        when (val outcome = ScanRules.classify(raw)) {
                            is ScanRules.Outcome.Link -> {
                                stopped = true
                                onScanned(outcome.link)
                            }

                            is ScanRules.Outcome.Rejected -> hint = outcome.reason
                        }
                    },
                    onError = { hint = it },
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (denied) "没给相机权限，扫不了。可以在系统设置里补开，或者用「从 crawlbase relay 同步」手填 PC 地址。" else hint,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color(0xA6000000), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (denied) {
                    Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
                        Text("再要一次权限")
                    }
                } else {
                    TextButton(onClick = onClose) {
                        Text("关闭", color = Color.White)
                    }
                }
            }
        }
    }

    if (!granted && !denied) {
        LaunchedEffect(Unit) { requestPermission.launch(Manifest.permission.CAMERA) }
    }
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** 相机取景 + QR 分析。绑定挂在 [LocalLifecycleOwner] 上，关页面即释放。 */
@Composable
private fun CameraPreview(
    onText: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { ContextCompat.getMainExecutor(context) }
    // ML Kit 的 task 回调在主线程，但 analyzer 可能与成功回调交错，用标志位挡住重复命中
    val handled = remember { booleanArrayOf(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            val mediaImage = proxy.image
            if (handled[0] || mediaImage == null) {
                proxy.close()
                return@setAnalyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    val raw = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { t -> t.isNotBlank() } }
                        ?: return@addOnSuccessListener
                    handled[0] = true
                    onText(raw)
                }
                .addOnFailureListener { onError("识别失败，再试一次") }
                .addOnCompleteListener { proxy.close() }
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }

        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure { onError("打不开相机：${it.message ?: it.javaClass.simpleName}") }
            },
            executor,
        )

        onDispose {
            handled[0] = true
            runCatching { providerFuture.get().unbindAll() }
            runCatching { scanner.close() }
            analysis.clearAnalyzer()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { view },
        update = {},
        modifier = Modifier.fillMaxSize(),
    )
}
