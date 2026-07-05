package cn.phonepad.ui

import android.os.Bundle
import android.widget.Button
import cn.phonepad.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class QrScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_scan)
        findViewById<Button>(R.id.btn_cancel_scan).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
