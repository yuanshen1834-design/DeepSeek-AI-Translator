package com.deeplab.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 翻译记录页面：查看 / 删除单条 / 清空全部 / 朗读译文（在线 Edge 引擎） */
class HistoryActivity : AppCompatActivity() {

    private lateinit var db: TranslationDbHelper
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private val records = mutableListOf<TranslationRecord>()
    private lateinit var adapter: HistoryAdapter

    private lateinit var ttsManager: TtsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = TranslationDbHelper(this)
        listView = findViewById(R.id.list_history)
        tvEmpty = findViewById(R.id.tv_empty)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_clear).setOnClickListener { confirmClear() }

        ttsManager = TtsManager(this)

        adapter = HistoryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            showDetail(records[position])
        }
        refresh()
    }

    override fun onDestroy() {
        ttsManager.release()
        super.onDestroy()
    }

    private fun refresh() {
        records.clear()
        records.addAll(db.getAll())
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClear() {
        if (records.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_records), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_all_title))
            .setMessage(getString(R.string.clear_all_msg, records.size))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                db.deleteAll()
                refresh()
                Toast.makeText(this, getString(R.string.cleared_all), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteOne(record: TranslationRecord) {
        db.delete(record.id)
        refresh()
        Toast.makeText(this, getString(R.string.record_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun speakRecord(record: TranslationRecord) {
        if (ttsManager.isBusy) {
            ttsManager.stop()
            return
        }
        val lang = Languages.byDisplay(record.tgtLang)
            ?: Languages.Language(record.tgtLang, record.tgtLang, "")
        ttsManager.speak(record.tgtText, lang, object : TtsManager.Listener {
            override fun onStart() = Unit

            override fun onSuccess(modeUsed: String) = Unit

            override fun onError(messageResId: Int) {
                runOnUiThread {
                    Toast.makeText(this@HistoryActivity, getString(messageResId), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun showDetail(record: TranslationRecord) {
        val msg = getString(R.string.detail_source) + "\n" + record.srcText +
            "\n\n" + getString(R.string.detail_target) + "\n" + record.tgtText
        AlertDialog.Builder(this)
            .setTitle("${record.srcLang} → ${record.tgtLang}")
            .setMessage(msg)
            .setPositiveButton(getString(R.string.read_aloud)) { _, _ ->
                speakRecord(record)
            }
            .setNeutralButton(getString(R.string.copy_text)) { _, _ -> copy(record.tgtText) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private inner class HistoryAdapter : BaseAdapter() {

        override fun getCount(): Int = records.size

        override fun getItem(position: Int): Any = records[position]

        override fun getItemId(position: Int): Long = records[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: View
            val holder: ViewHolder
            if (convertView == null) {
                view = LayoutInflater.from(this@HistoryActivity)
                    .inflate(R.layout.item_history, parent, false)
                holder = ViewHolder(view)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }
            val record = records[position]
            holder.tvLangs.text = "${record.srcLang} → ${record.tgtLang}"
            holder.tvTime.text =
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
            holder.tvSrc.text = record.srcText
            holder.tvTgt.text = record.tgtText
            holder.btnDelete.setOnClickListener { deleteOne(record) }
            holder.btnSpeak.setOnClickListener { speakRecord(record) }
            holder.btnCopy.setOnClickListener { copy(record.tgtText) }
            return view
        }

        private inner class ViewHolder(view: View) {
            val tvLangs: TextView = view.findViewById(R.id.tv_langs)
            val tvTime: TextView = view.findViewById(R.id.tv_time)
            val tvSrc: TextView = view.findViewById(R.id.tv_src)
            val tvTgt: TextView = view.findViewById(R.id.tv_tgt)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
            val btnSpeak: ImageButton = view.findViewById(R.id.btn_speak)
            val btnCopy: ImageButton = view.findViewById(R.id.btn_copy)
        }
    }
}
