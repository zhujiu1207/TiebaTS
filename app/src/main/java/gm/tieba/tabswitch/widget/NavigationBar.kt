package gm.tieba.tabswitch.widget

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.util.getColor
import gm.tieba.tabswitch.util.getObjectField

class NavigationBar(thisObject: Any) : XposedContext() {

    private val mNavigationBar: Any? = getObjectField(
        thisObject,
        "com.baidu.tbadk.core.view.NavigationBar"
    )

    // 宿主 Activity recreate 后导航栏可能保留旧按钮: 按文本查重, 避免叠加
    fun hasTextButton(text: String): Boolean {
        val root = mNavigationBar as? View ?: return false
        return subtreeHasText(root, text)
    }

    private fun subtreeHasText(view: View, text: String): Boolean {
        if (view is TextView && view.text?.toString() == text) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (subtreeHasText(view.getChildAt(i), text)) return true
            }
        }
        return false
    }

    fun addTextButton(text: String?, l: View.OnClickListener?) {
        val controlAlignClass = findClass("com.baidu.tbadk.core.view.NavigationBar\$ControlAlign")
        val horizontalRight = controlAlignClass.enumConstants.find { it.toString() == "HORIZONTAL_RIGHT" }
            ?: throw IllegalStateException("HORIZONTAL_RIGHT enum constant not found")
        val textView = XposedHelpers.callMethod(
            mNavigationBar,
            "addTextButton", horizontalRight, text, l
        ) as TextView
        textView.setTextColor(getColor("CAM_X0105"))
    }

    fun setTitleText(title: String?) {
        title?.let {
            XposedHelpers.callMethod(mNavigationBar, "setTitleText", it)
        }
    }

    fun setCenterTextTitle(title: String?) {
        title?.let {
            XposedHelpers.callMethod(mNavigationBar, "setCenterTextTitle", it)
        }
    }
}
