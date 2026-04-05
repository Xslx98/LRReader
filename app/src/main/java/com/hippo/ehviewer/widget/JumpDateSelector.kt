package com.hippo.ehviewer.widget

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.DatePicker
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.hippo.ehviewer.R
import java.util.Calendar

class JumpDateSelector @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes), DatePicker.OnDateChangedListener {

    private val mRadioGroup: MyRadioGroup
    private var mSelectRadio: RadioButton? = null
    private val foundMessage: TextView
    private var onTimeSelectedListener: OnTimeSelectedListener? = null
    private val pickDateButton: Button
    private val gotoJumpButton: Button
    private val datePicker: DatePicker

    private var dateJumpType = DATE_NODE_TYPE

    private var dayOfMonthSelected: Int
    private var monthOfYearSelected: Int
    private var yearSelected: Int

    private var radioButtonOriginColor: ColorStateList? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.gallery_list_jump_selector, this)
        datePicker = findViewById(R.id.date_picker_view)
        val calendar = Calendar.getInstance()
        yearSelected = calendar.get(Calendar.YEAR)
        monthOfYearSelected = calendar.get(Calendar.MONTH)
        dayOfMonthSelected = calendar.get(Calendar.DAY_OF_MONTH)
        datePicker.init(yearSelected, monthOfYearSelected, dayOfMonthSelected, this)
        datePicker.maxDate = calendar.timeInMillis

        val minDate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Calendar.Builder().setDate(2007, 3, 20).build()
        } else {
            Calendar.getInstance().apply { set(2007, 3, 20) }
        }
        datePicker.minDate = minDate.timeInMillis

        pickDateButton = findViewById(R.id.picker_date)
        gotoJumpButton = findViewById(R.id.goto_jump)
        pickDateButton.setOnClickListener { onClickPickerDateButton() }
        gotoJumpButton.setOnClickListener { buildJumpParamAndGoto() }
        mRadioGroup = findViewById(R.id.jump_date_picker_group)
        mRadioGroup.setOnCheckedChangeListener { radioGroup, i -> onSelectChange(radioGroup, i) }
        foundMessage = findViewById(R.id.found_message)
    }

    private fun buildJumpParamAndGoto() {
        val urlAppend = if (dateJumpType == DATE_PICKER_TYPE) {
            datePickerAppendBuild()
        } else {
            nodePickerAppendBuild()
        }
        onTimeSelectedListener?.onTimeSelected(urlAppend)
    }

    private fun datePickerAppendBuild(): String {
        return "seek=$yearSelected-$monthOfYearSelected-$dayOfMonthSelected"
    }

    private fun nodePickerAppendBuild(): String {
        val radio = mSelectRadio ?: return ""
        val param = when (radio.id) {
            R.id.jump_3d -> "3d"
            R.id.jump_1w -> "1w"
            R.id.jump_2w -> "2w"
            R.id.jump_1m -> "1m"
            R.id.jump_6m -> "6m"
            R.id.jump_1y -> "1y"
            R.id.jump_2y -> "2y"
            else -> "1d"
        }
        return "jump=$param"
    }

    private fun onClickPickerDateButton() {
        if (dateJumpType == DATE_PICKER_TYPE) {
            datePicker.visibility = GONE
            mRadioGroup.visibility = VISIBLE
            dateJumpType = DATE_NODE_TYPE
            pickDateButton.setText(R.string.gallery_list_select_jump_date)
        } else {
            datePicker.visibility = VISIBLE
            mRadioGroup.visibility = GONE
            dateJumpType = DATE_PICKER_TYPE
            pickDateButton.setText(R.string.gallery_list_select_jump_node)
        }
    }

    private fun onSelectChange(radioGroup: RadioGroup, i: Int) {
        if (i == -1) return
        if (radioButtonOriginColor == null) {
            mSelectRadio = radioGroup.findViewById(i)
            radioButtonOriginColor = mSelectRadio!!.textColors
        } else {
            mSelectRadio!!.setTextColor(radioButtonOriginColor)
            mSelectRadio = radioGroup.findViewById(i)
        }
        mSelectRadio!!.setTextColor(mSelectRadio!!.highlightColor)
    }

    fun setFoundMessage(message: String?) {
        if (message.isNullOrEmpty()) {
            foundMessage.visibility = GONE
        } else {
            foundMessage.visibility = VISIBLE
            foundMessage.text = resources.getString(
                R.string.gallery_list_time_jump_dialog_found_message, message
            )
        }
    }

    fun setOnTimeSelectedListener(listener: OnTimeSelectedListener?) {
        onTimeSelectedListener = listener
    }

    override fun onDateChanged(view: DatePicker, year: Int, monthOfYear: Int, dayOfMonth: Int) {
        yearSelected = year
        monthOfYearSelected = monthOfYear + 1
        dayOfMonthSelected = dayOfMonth
    }

    fun interface OnTimeSelectedListener {
        fun onTimeSelected(urlAppend: String)
    }

    companion object {
        private const val DATE_PICKER_TYPE = 2
        private const val DATE_NODE_TYPE = 1
    }
}
