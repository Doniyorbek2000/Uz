package uz.uzkey.keyboard.debug

import android.app.Activity
import android.os.Bundle
import uz.uzkey.keyboard.R

/** Hosts one editor per input type; used only by the instrumented keyboard tests. */
class TestFieldsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_fields)
    }
}
