package com.votry.productstudio.ml

object ModelConfig {
    const val FILE_NAME = "birefnet-general-lite.onnx"
    const val URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx"
    const val MIN_BYTES = 200_000_000L
    const val SIZE = 1024
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val std = floatArrayOf(0.229f, 0.224f, 0.225f)
}
