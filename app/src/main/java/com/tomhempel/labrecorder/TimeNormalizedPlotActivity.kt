package com.tomhempel.labrecorder

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File
import java.io.FileReader
import java.io.BufferedReader
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.updateLayoutParams
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet

class TimeNormalizedPlotActivity : AppCompatActivity() {
    private lateinit var hrChart: LineChart
    private lateinit var rrChart: LineChart
    private val intervalColors = listOf(
        Color.argb(128, 128, 128, 128),  // Gray
        Color.argb(128, 128, 64, 64),    // Dark Red
        Color.argb(128, 64, 128, 64),    // Dark Green
        Color.argb(128, 64, 64, 128),    // Dark Blue
        Color.argb(128, 128, 128, 64),   // Dark Yellow
        Color.argb(128, 128, 64, 128)    // Dark Purple
    )

    data class TimestampEvent(
        val timestamp: Long,
        val eventType: String
    )

    data class DataPoint(
        val timestamp: Long,
        val value: Float
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create ScrollView for scrollable content
        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        // Create layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 32, 16, 32) // Added top and bottom padding
        }

        // Create stats cards container
        val statsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            weightSum = 2f
        }

        // Create stats cards for each participant
        val participant1Stats = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, 8, 0)
            }
            radius = 12f
            cardElevation = 8f
            setCardBackgroundColor(Color.parseColor("#1F1F1F"))
        }

        val participant2Stats = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(8, 0, 0, 0)
            }
            radius = 12f
            cardElevation = 8f
            setCardBackgroundColor(Color.parseColor("#1F1F1F"))
        }

        // Create TextViews for stats with better styling
        val participant1Text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 20, 20, 20)
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(4f, 1.2f)
        }

        val participant2Text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(20, 20, 20, 20)
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(4f, 1.2f)
        }

        participant1Stats.addView(participant1Text)
        participant2Stats.addView(participant2Text)
        statsContainer.addView(participant1Stats)
        statsContainer.addView(participant2Stats)
        rootLayout.addView(statsContainer)

        // Create HR Chart Card
        val hrCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            radius = 8f
            cardElevation = 4f
        }
        hrChart = LineChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            description.isEnabled = false
        }
        hrCard.addView(hrChart)
        rootLayout.addView(hrCard)

        // Create RR Chart Card
        val rrCard = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            radius = 8f
            cardElevation = 4f
        }
        rrChart = LineChart(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            description.isEnabled = false
        }
        rrCard.addView(rrChart)
        rootLayout.addView(rrCard)

        // Add ScrollView to the content
        scrollView.addView(rootLayout)
        setContentView(scrollView)

        // Configure charts
        setupChart(hrChart, "Heart Rate (BPM)")
        setupChart(rrChart, "RR Interval (ms)")

        // Load and display data
        val recordingPath = intent.getStringExtra("RECORDING_PATH") ?: return
        loadAndDisplayData(recordingPath, participant1Text, participant2Text)
    }

    private fun setupChart(chart: LineChart, yAxisLabel: String) {
        chart.apply {
            // Disable interactions
            setTouchEnabled(false)
            setPinchZoom(false)
            setScaleEnabled(false)
            isDragEnabled = false

            // Configure legend
            legend.apply {
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                verticalAlignment = Legend.LegendVerticalAlignment.TOP
                orientation = Legend.LegendOrientation.HORIZONTAL
                setDrawInside(false)
                form = Legend.LegendForm.LINE
            }

            // Configure axes
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                labelRotationAngle = 0f
                axisMinimum = 0f
                textSize = 12f
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        return "${value.toInt()}s"
                    }
                }
            }

            axisLeft.apply {
                setDrawGridLines(true)
                textSize = 12f
                // Fix: Use description instead of axisLabel
                description.text = yAxisLabel
            }

            axisRight.isEnabled = false
            
            // Add padding for better visibility
            setExtraOffsets(8f, 16f, 8f, 8f)
        }
    }

    private fun loadAndDisplayData(
        recordingPath: String, 
        participant1Text: TextView,
        participant2Text: TextView
    ) {
        val recordingDir = File(recordingPath)
        val timestampsFile = File(recordingDir, "timestamps.csv")
        
        // Load global events
        val events = loadTimestamps(timestampsFile)
        val firstTimestamp = events.minOfOrNull { it.timestamp } ?: 0L

        // Load participant data
        val participants = listOf("Participant_1", "Participant_2")
        val participantColors = listOf(Color.BLUE, Color.RED)

        val hrDataSets = mutableListOf<ILineDataSet>()
        val rrDataSets = mutableListOf<ILineDataSet>()
        val statsBuilders = Array(2) { StringBuilder() }

        participants.forEachIndexed { index, participant ->
            val participantDir = File(recordingDir, participant)
            val hrFile = File(participantDir, "hr.csv")
            val rrFile = File(participantDir, "rr.csv")

            statsBuilders[index].append("$participant\n\n")

            // Load HR data
            val hrData = loadDataPoints(hrFile)
            if (hrData.isNotEmpty()) {
                val hrEntries = hrData.map { 
                    Entry(
                        (it.timestamp - firstTimestamp) / 1000f,
                        it.value
                    )
                }
                val hrDataSet = LineDataSet(hrEntries, "$participant HR").apply {
                    color = participantColors[index]
                    setDrawCircles(true)
                    circleRadius = 2f
                    circleColors = listOf(participantColors[index])
                    lineWidth = 2f
                    mode = LineDataSet.Mode.LINEAR
                }
                hrDataSets.add(hrDataSet)

                // Calculate statistics
                statsBuilders[index].append("Heart Rate:\n")
                statsBuilders[index].append("  Min: ${hrData.minOf { it.value }.toInt()} BPM\n")
                statsBuilders[index].append("  Max: ${hrData.maxOf { it.value }.toInt()} BPM\n")
                statsBuilders[index].append("  Avg: ${hrData.map { it.value }.average().toInt()} BPM\n\n")
            }

            // Load RR data
            val rrData = loadDataPoints(rrFile)
            if (rrData.isNotEmpty()) {
                val rrEntries = rrData.map { 
                    Entry(
                        (it.timestamp - firstTimestamp) / 1000f,
                        it.value
                    )
                }
                val rrDataSet = LineDataSet(rrEntries, "$participant RR").apply {
                    color = participantColors[index]
                    setDrawCircles(true)
                    circleRadius = 2f
                    circleColors = listOf(participantColors[index])
                    lineWidth = 2f
                    mode = LineDataSet.Mode.LINEAR
                }
                rrDataSets.add(rrDataSet)

                // Calculate statistics
                statsBuilders[index].append("RR Intervals:\n")
                statsBuilders[index].append("  Min: ${rrData.minOf { it.value }.toInt()} ms\n")
                statsBuilders[index].append("  Max: ${rrData.maxOf { it.value }.toInt()} ms\n")
                statsBuilders[index].append("  Avg: ${rrData.map { it.value }.average().toInt()} ms")
            }
        }

        // Update statistics
        participant1Text.text = statsBuilders[0].toString()
        participant2Text.text = statsBuilders[1].toString()

        // Set chart data
        hrChart.setData(LineData(hrDataSets))
        rrChart.setData(LineData(rrDataSets))

        // Add event markers and intervals
        addEventMarkersAndIntervals(events, firstTimestamp, hrChart)
        addEventMarkersAndIntervals(events, firstTimestamp, rrChart)

        // Refresh charts
        hrChart.invalidate()
        rrChart.invalidate()
    }

    private fun loadTimestamps(file: File): List<TimestampEvent> {
        if (!file.exists()) return emptyList()
        
        return try {
            BufferedReader(FileReader(file)).useLines { lines ->
                lines.drop(1) // Skip header
                    .map { line ->
                        val parts = line.split(",")
                        TimestampEvent(
                            timestamp = parts[0].toLong(),
                            eventType = parts[1]
                        )
                    }.toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun loadDataPoints(file: File): List<DataPoint> {
        if (!file.exists()) return emptyList()
        
        return try {
            BufferedReader(FileReader(file)).useLines { lines ->
                lines.drop(1) // Skip header
                    .map { line ->
                        val parts = line.split(",")
                        DataPoint(
                            timestamp = parts[0].toLong(),
                            value = parts[1].toFloat()
                        )
                    }.toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun addEventMarkersAndIntervals(
        events: List<TimestampEvent>,
        firstTimestamp: Long,
        chart: LineChart
    ) {
        var currentIntervalStart: Float? = null
        var intervalIndex = 0
        val intervalDataSets = mutableListOf<ILineDataSet>()

        events.forEach { event ->
            val normalizedTime = (event.timestamp - firstTimestamp) / 1000f

            when (event.eventType) {
                "manual_mark" -> {
                    // Add vertical line for manual mark - white and prominent
                    val line = com.github.mikephil.charting.components.LimitLine(normalizedTime).apply {
                        lineWidth = 1f
                        lineColor = Color.WHITE
                        enableDashedLine(15f, 8f, 0f)
                        label = "" // No label
                    }
                    chart.xAxis.addLimitLine(line)
                }
                "interval_start" -> {
                    currentIntervalStart = normalizedTime
                }
                "interval_end" -> {
                    currentIntervalStart?.let { start ->
                        // Create filled area for interval using a background dataset
                        var color = intervalColors[intervalIndex % intervalColors.size]
                        
                        // Create entries for the interval area
                        val entries = listOf(
                            Entry(start, chart.axisLeft.axisMinimum),
                            Entry(start, chart.axisLeft.axisMaximum),
                            Entry(normalizedTime, chart.axisLeft.axisMaximum),
                            Entry(normalizedTime, chart.axisLeft.axisMinimum)
                        )
                        
                        val intervalDataSet = LineDataSet(entries, "Interval ${intervalIndex + 1}").apply {
                            setDrawFilled(true)
                            fillColor = color
                            fillAlpha = 128
                            color = Color.TRANSPARENT
                            setDrawCircles(false)
                            setDrawValues(false)
                            isHighlightEnabled = false
                            axisDependency = YAxis.AxisDependency.LEFT
                        }
                        
                        intervalDataSets.add(intervalDataSet)
                        intervalIndex++
                    }
                    currentIntervalStart = null
                }
            }
        }

        // Add interval datasets to the chart
        if (intervalDataSets.isNotEmpty()) {
            // Get current datasets
            val currentDataSets = chart.data?.dataSets?.toMutableList() ?: mutableListOf()
            
            // Create combined dataset list
            val allDataSets = mutableListOf<ILineDataSet>()
            allDataSets.addAll(intervalDataSets)
            allDataSets.addAll(currentDataSets)
            
            // Create and set new data
            val newData = LineData(allDataSets)
            chart.setData(newData)
            chart.invalidate()
        }
    }
} 