package com.liubai.guard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 卸载锁：开着它，桌面和应用信息里的「卸载」会被系统直接拒绝。只有在留白里连点 10 次才会自己解开。 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(c: Context, i: Intent): CharSequence =
        "留白的卸载锁还在守护中。想卸载，请回到留白里连点 10 次「确定」。"
}

object Admin {
    fun comp(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun on(c: Context) = try { dpm(c).isAdminActive(comp(c)) } catch (x: Exception) { false }
    fun askIntent(c: Context) = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp(c))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "开启后，留白不能被直接卸载。想卸载时，在留白里连点 10 次「确定」，锁会自己解开。")
    fun release(c: Context) { try { if (on(c)) { dpm(c).removeActiveAdmin(comp(c)); Diag.log("卸载锁已解开") } } catch (x: Exception) {} }
    /** 今天没有任何守护了，就不再锁着卸载。 */
    fun tidy(c: Context) { if (on(c) && !Store.protectActive(c)) release(c) }
}
