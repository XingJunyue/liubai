package com.liubai.guard

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Todo(val id: String, val text: String, val tag: String, val done: Boolean = false)
/** 预约番茄：days 用 1–7 表示周一到周日。 */
data class Plan(val id: String, val days: Set<Int>, val at: Int, val on: Boolean = true)

/** 专注的标签、待办和预约。 */
object Tasks {
    private fun p(c: Context) = c.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    fun tags(c: Context): List<String> = (p(c).getString("tags", null) ?: tr("学习,工作,阅读")).split(',').filter { it.isNotBlank() }
    fun setTags(c: Context, l: List<String>) = p(c).edit().putString("tags", l.joinToString(",") { it.replace(",", " ") }).apply()

    fun todos(c: Context): List<Todo> {
        val a = try { JSONArray(p(c).getString("todos", "[]")) } catch (x: Exception) { JSONArray() }
        return (0 until a.length()).map { val j = a.getJSONObject(it); Todo(j.optString("id"), j.optString("t"), j.optString("g"), j.optBoolean("d")) }
    }
    fun setTodos(c: Context, l: List<Todo>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("g", it.tag).put("d", it.done)) }
        p(c).edit().putString("todos", a.toString()).apply()
    }

    /** 当前在专注什么：标签 + 可选的待办。 */
    fun curTag(c: Context) = p(c).getString("cur", null) ?: tags(c).firstOrNull() ?: tr("学习")
    fun curTodo(c: Context) = p(c).getString("curTodo", "") ?: ""
    fun setCur(c: Context, tag: String, todo: String = "") = p(c).edit().putString("cur", tag).putString("curTodo", todo).apply()

    fun addFocus(c: Context, ms: Long) {
        if (ms <= 0) return
        val k = "m_${Store.today()}_${curTag(c)}"
        p(c).edit().putLong(k, p(c).getLong(k, 0L) + ms).putLong("all_${curTag(c)}", p(c).getLong("all_${curTag(c)}", 0L) + ms).apply()
    }
    fun tagToday(c: Context): List<Pair<String, Int>> = tags(c).map { it to (p(c).getLong("m_${Store.today()}_$it", 0L) / 60_000L).toInt() }.filter { it.second > 0 }

    fun plans(c: Context): List<Plan> {
        val a = try { JSONArray(p(c).getString("plans", "[]")) } catch (x: Exception) { JSONArray() }
        return (0 until a.length()).map { val j = a.getJSONObject(it); val d = j.optJSONArray("d") ?: JSONArray()
            Plan(j.optString("id"), (0 until d.length()).map { d.getInt(it) }.toSet(), j.optInt("a", 480), j.optBoolean("o", true)) }
    }
    fun setPlans(c: Context, l: List<Plan>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("d", JSONArray(it.days.toList())).put("a", it.at).put("o", it.on)) }
        p(c).edit().putString("plans", a.toString()).apply()
    }

    fun weekday(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }

    /** 守护服务每次检查时调用：到了预约的时间就自动开始专注（每个预约每天只触发一次，错过 3 分钟就不补）。 */
    fun checkPlans(c: Context) {
        if (Focus.phase(c) != Focus.IDLE) return
        val m = Rules.nowMin(); val d = weekday(); val t = Store.today()
        for (pl in plans(c)) {
            if (!pl.on || d !in pl.days || m < pl.at || m > pl.at + 3) continue
            val k = "fired_${pl.id}"
            if (p(c).getString(k, "") == t) continue
            p(c).edit().putString(k, t).apply()
            Focus.start(c, Focus.WORK)
            Focus.notifyStart(c)
            return
        }
    }
}

val DAY_NAMES get() = (1..7).map { dayShort(it) }

/** 专注页上的一个小标签：现在在专注什么。点开选标签、待办。 */
@Composable
fun TaskChip(enabled: Boolean) {
    val c = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var v by remember { mutableStateOf(0) }
    val tag = remember(v, open) { Tasks.curTag(c) }
    val todo = remember(v, open) { Tasks.curTodo(c) }
    Row(
        Modifier.padding(top = 14.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
            .clickable(enabled = enabled) { open = true }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tag, fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold)
        if (todo.isNotEmpty()) Text(" · $todo", fontSize = 13.sp, color = C.Ink, maxLines = 1)
        if (enabled) Text("  ›", fontSize = 13.sp, color = C.Faint)
    }
    if (open) TaskSheet { open = false; v++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(onClose: () -> Unit) {
    val c = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tags by remember { mutableStateOf(Tasks.tags(c)) }
    var todos by remember { mutableStateOf(Tasks.todos(c)) }
    var tag by remember { mutableStateOf(Tasks.curTag(c)) }
    var cur by remember { mutableStateOf(Tasks.curTodo(c)) }
    var input by remember { mutableStateOf("") }
    var newTag by remember { mutableStateOf("") }
    fun pick(t: String, td: String = "") { tag = t; cur = td; Tasks.setCur(c, t, td) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("这次专注做什么"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            FlowRowLite {
                for (t in tags) {
                    val on = t == tag
                    Text(t, fontSize = 13.sp, color = if (on) Color.White else C.Ink,
                        modifier = Modifier.padding(end = 8.dp, top = 10.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) C.Clay else C.Sand).clickable { pick(t) }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
                Box(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    if (newTag.isEmpty()) Text(tr("＋ 标签"), fontSize = 13.sp, color = C.Faint)
                    BasicTextField(newTag, { newTag = it.take(6) }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = C.Ink),
                        modifier = Modifier.width(64.dp),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                            val n = newTag.trim(); if (n.isNotEmpty() && n !in tags) { tags = tags + n; Tasks.setTags(c, tags); pick(n) }; newTag = ""
                        }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                }
            }
            val mins = remember { Tasks.tagToday(c) }
            if (mins.isNotEmpty()) Text(tr("今天：") + mins.joinToString(if (Tr.cjk) "，" else ", ") { tr("{0} {1} 分钟", it.first, it.second) }, fontSize = 12.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp))

            Text(tr("待办"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text(tr("写一件要做的事"), fontSize = 14.sp, color = C.Faint)
                    BasicTextField(input, { input = it.take(30) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
                }
                Text(tr("添加"), fontSize = 14.sp, color = if (input.isBlank()) C.Faint else C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = input.isNotBlank()) {
                        todos = listOf(Todo("t" + System.currentTimeMillis(), input.trim(), tag)) + todos; Tasks.setTodos(c, todos); input = ""
                    }.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            val shown = todos.sortedBy { it.done }.take(30)
            for (td in shown) {
                val on = td.text == cur && !td.done
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.ClayPale else Color.Transparent)
                    .clickable(enabled = !td.done) { pick(td.tag.ifBlank { tag }, td.text) }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape)
                        .then(if (td.done) Modifier.background(C.Sage) else Modifier.border(2.dp, Color(0xFFDCCFC2), CircleShape))
                        .clickable {
                            todos = todos.map { if (it.id == td.id) it.copy(done = !it.done) else it }; Tasks.setTodos(c, todos)
                            if (td.text == cur) { cur = ""; Tasks.setCur(c, tag, "") }
                        }, contentAlignment = Alignment.Center) { if (td.done) Text("✓", color = Color.White, fontSize = 11.sp) }
                    Text(td.text, fontSize = 14.sp, color = if (td.done) C.Faint else C.Ink,
                        textDecoration = if (td.done) TextDecoration.LineThrough else null, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    Text(td.tag, fontSize = 11.sp, color = C.Faint)
                    Text("  ×", fontSize = 14.sp, color = C.Faint, modifier = Modifier.clickable { todos = todos.filter { it.id != td.id }; Tasks.setTodos(c, todos) })
                }
            }
            if (todos.any { it.done }) Text(tr("清掉已完成"), fontSize = 13.sp, color = C.Soft,
                modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { todos = todos.filter { !it.done }; Tasks.setTodos(c, todos) }.padding(vertical = 6.dp))
            Spacer(Modifier.height(10.dp))
            PrimaryButton(tr("好了")) { onClose() }
        }
    }
}

/** 专注设置里的「预约」：每周几的几点自动开始一个番茄。 */
@Composable
fun PlanEditor() {
    val c = LocalContext.current
    var plans by remember { mutableStateOf(Tasks.plans(c)) }
    fun save(l: List<Plan>) { plans = l; Tasks.setPlans(c, l) }
    Text(tr("预约番茄"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
    Text(tr("到点自动开始专注，留白会提醒你"), fontSize = 12.sp, color = C.Faint)
    for (pl in plans) {
        Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Rules.clock(pl.at), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (pl.on) C.Clay else C.Faint, modifier = Modifier.weight(1f))
                Text("−", fontSize = 20.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { save(plans.map { if (it.id == pl.id) it.copy(at = (it.at - 15 + 1440) % 1440) else it }) }.padding(horizontal = 10.dp))
                Text("+", fontSize = 20.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { save(plans.map { if (it.id == pl.id) it.copy(at = (it.at + 15) % 1440) else it }) }.padding(horizontal = 10.dp))
                Text(tr("删除"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.clickable { save(plans.filter { it.id != pl.id }) }.padding(start = 8.dp))
            }
            DayPicker(pl.days) { d -> save(plans.map { if (it.id == pl.id) it.copy(days = d) else it }) }
        }
    }
    Text(tr("＋ 添加预约"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable {
            save(plans + Plan("p" + System.currentTimeMillis(), setOf(1, 2, 3, 4, 5), 20 * 60))
        }.padding(vertical = 6.dp))
}

@Composable
fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 1..7) {
            val on = d in days
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (on) C.Clay else C.Card)
                .clickable { onChange(if (on) days - d else days + d) }, contentAlignment = Alignment.Center) {
                Text(DAY_NAMES[d - 1], fontSize = 12.sp, color = if (on) Color.White else C.Soft)
            }
        }
    }
}
