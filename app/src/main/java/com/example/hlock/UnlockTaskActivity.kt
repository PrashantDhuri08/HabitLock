package com.example.hlock

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlin.random.Random

class UnlockTaskActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sharedPrefs: SharedPreferences
    private var blockedPackage: String? = null

    // Views
    private lateinit var layoutTaskSelection: LinearLayout
    private lateinit var layoutTaskExecution: LinearLayout
    private lateinit var tvTaskTitle: TextView
    private lateinit var tvTaskInstruction: TextView
    private lateinit var tvTaskProgress: TextView
    private lateinit var pbTask: ProgressBar

    // Math
    private lateinit var layoutMathProblem: LinearLayout
    private lateinit var tvMathQuestion: TextView
    private lateinit var etMathAnswer: TextInputEditText
    private lateinit var btnSubmitAnswer: MaterialButton

    // Phrase
    private lateinit var layoutTypePhrase: LinearLayout
    private lateinit var tvPhraseToType: TextView
    private lateinit var etPhraseInput: TextInputEditText
    private lateinit var btnSubmitPhrase: MaterialButton

    // Step tracking
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var initialSteps = -1f
    private var stepsNeeded = 100

    // Math task
    private var mathSolved = 0
    private var mathTotal = 3
    private var currentMathAnswer = 0

    // Cooldown
    private var cooldownTimer: CountDownTimer? = null

    // Focus phrases
    private val focusPhrases = listOf(
        "I choose to focus on what truly matters today",
        "My time is valuable and I will use it wisely",
        "I am in control of my habits and my future",
        "Every moment of focus brings me closer to my goals",
        "I will not let distractions steal my potential"
    )

    companion object {
        const val UNLOCK_DURATION_MS = 15 * 60 * 1000L // 15 minutes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock_task)

        sharedPrefs = getSharedPreferences("AppLimits", Context.MODE_PRIVATE)
        blockedPackage = intent.getStringExtra("blocked_package")

        initViews()
        setupTaskCards()

        findViewById<MaterialButton>(R.id.btnGoBack).setOnClickListener { finish() }

        val tvMessage = findViewById<TextView>(R.id.tvUnlockMessage)
        val appName = blockedPackage?.let {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            } catch (e: Exception) { it }
        } ?: "this app"
        tvMessage.text = "Complete a task to unlock $appName for 15 minutes"
    }

    private fun initViews() {
        layoutTaskSelection = findViewById(R.id.layoutTaskSelection)
        layoutTaskExecution = findViewById(R.id.layoutTaskExecution)
        tvTaskTitle = findViewById(R.id.tvTaskTitle)
        tvTaskInstruction = findViewById(R.id.tvTaskInstruction)
        tvTaskProgress = findViewById(R.id.tvTaskProgress)
        pbTask = findViewById(R.id.pbTask)

        layoutMathProblem = findViewById(R.id.layoutMathProblem)
        tvMathQuestion = findViewById(R.id.tvMathQuestion)
        etMathAnswer = findViewById(R.id.etMathAnswer)
        btnSubmitAnswer = findViewById(R.id.btnSubmitAnswer)

        layoutTypePhrase = findViewById(R.id.layoutTypePhrase)
        tvPhraseToType = findViewById(R.id.tvPhraseToType)
        etPhraseInput = findViewById(R.id.etPhraseInput)
        btnSubmitPhrase = findViewById(R.id.btnSubmitPhrase)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        findViewById<MaterialButton>(R.id.btnCancelTask).setOnClickListener {
            layoutTaskExecution.visibility = View.GONE
            layoutTaskSelection.visibility = View.VISIBLE
            sensorManager?.unregisterListener(this)
            cooldownTimer?.cancel()
        }
    }

    private fun setupTaskCards() {
        findViewById<View>(R.id.cardWalkSteps).setOnClickListener {
            if (stepSensor != null) {
                startWalkTask()
            } else {
                Toast.makeText(this, "Step sensor not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.cardMathProblems).setOnClickListener { startMathTask() }
        findViewById<View>(R.id.cardCooldown).setOnClickListener { startCooldownTask() }
        findViewById<View>(R.id.cardTypePhrase).setOnClickListener { startPhraseTask() }
        findViewById<View>(R.id.cardReadArticle).setOnClickListener { startReadingTask() }
    }

    private fun showTaskExecution() {
        layoutTaskSelection.visibility = View.GONE
        layoutTaskExecution.visibility = View.VISIBLE
        layoutMathProblem.visibility = View.GONE
        layoutTypePhrase.visibility = View.GONE
    }

    // =====================
    // TASK 1: Walk Steps
    // =====================
    private fun startWalkTask() {
        showTaskExecution()
        tvTaskTitle.text = "🚶 Walk 100 Steps"
        tvTaskInstruction.text = "Start walking! Your steps are being counted."
        tvTaskProgress.text = "0 / $stepsNeeded"
        pbTask.max = stepsNeeded
        pbTask.progress = 0

        initialSteps = -1f
        sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            if (initialSteps < 0) {
                initialSteps = event.values[0]
            }
            val stepsTaken = (event.values[0] - initialSteps).toInt()
            tvTaskProgress.text = "$stepsTaken / $stepsNeeded"
            pbTask.progress = stepsTaken.coerceAtMost(stepsNeeded)

            if (stepsTaken >= stepsNeeded) {
                sensorManager?.unregisterListener(this)
                onTaskCompleted()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =====================
    // TASK 2: Math Problems
    // =====================
    private fun startMathTask() {
        showTaskExecution()
        layoutMathProblem.visibility = View.VISIBLE
        tvTaskTitle.text = "🧮 Solve Math Problems"
        tvTaskInstruction.text = "Solve $mathTotal problems correctly"
        mathSolved = 0
        pbTask.max = mathTotal
        pbTask.progress = 0
        generateMathProblem()

        btnSubmitAnswer.setOnClickListener {
            val userAnswer = etMathAnswer.text.toString().toIntOrNull()
            if (userAnswer == currentMathAnswer) {
                mathSolved++
                pbTask.progress = mathSolved
                tvTaskProgress.text = "$mathSolved / $mathTotal"
                etMathAnswer.setText("")

                if (mathSolved >= mathTotal) {
                    onTaskCompleted()
                } else {
                    generateMathProblem()
                }
            } else {
                etMathAnswer.error = "Wrong! Try again"
                etMathAnswer.setText("")
            }
        }
    }

    private fun generateMathProblem() {
        val a = Random.nextInt(10, 99)
        val b = Random.nextInt(10, 99)
        val ops = listOf("+", "-", "×")
        val op = ops.random()

        currentMathAnswer = when (op) {
            "+" -> a + b
            "-" -> a - b
            "×" -> a * b
            else -> a + b
        }

        tvMathQuestion.text = "$a $op $b = ?"
        tvTaskProgress.text = "$mathSolved / $mathTotal"
        etMathAnswer.setText("")
        etMathAnswer.requestFocus()
    }

    // =====================
    // TASK 3: Cooldown Timer
    // =====================
    private fun startCooldownTask() {
        showTaskExecution()
        tvTaskTitle.text = "⏰ Cooldown Timer"
        tvTaskInstruction.text = "Wait patiently. Stay on this screen."
        pbTask.max = 100

        val totalMs = 5 * 60 * 1000L // 5 minutes

        cooldownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(remaining: Long) {
                val secsLeft = (remaining / 1000).toInt()
                val mins = secsLeft / 60
                val secs = secsLeft % 60
                tvTaskProgress.text = String.format("%d:%02d", mins, secs)
                val elapsed = totalMs - remaining
                pbTask.progress = ((elapsed.toFloat() / totalMs) * 100).toInt()
            }

            override fun onFinish() {
                tvTaskProgress.text = "0:00"
                pbTask.progress = 100
                onTaskCompleted()
            }
        }.start()
    }

    // =====================
    // TASK 4: Type Phrase
    // =====================
    private fun startPhraseTask() {
        showTaskExecution()
        layoutTypePhrase.visibility = View.VISIBLE
        tvTaskTitle.text = "📖 Type Focus Phrase"
        tvTaskInstruction.text = "Type the phrase below exactly as shown"
        tvTaskProgress.visibility = View.GONE
        pbTask.visibility = View.GONE

        val phrase = focusPhrases.random()
        tvPhraseToType.text = "\"$phrase\""

        btnSubmitPhrase.setOnClickListener {
            val typed = etPhraseInput.text.toString().trim()
            if (typed.equals(phrase, ignoreCase = true)) {
                onTaskCompleted()
            } else {
                etPhraseInput.error = "Doesn't match. Please type it exactly."
            }
        }
    }

    // =====================
    // TASK 5: Productive Reading
    // =====================
    private fun startReadingTask() {
        showTaskExecution()
        tvTaskTitle.text = "💡 Productive Reading"
        tvTaskInstruction.text = "Opening a useful article. Read for 1 minute to unlock."
        pbTask.max = 60
        pbTask.progress = 0
        tvTaskProgress.text = "Initializing..."

        val productiveUrls = listOf(
            "https://www.khanacademy.org",
            "https://en.wikipedia.org/wiki/Special:Random",
            "https://www.ted.com",
            "https://www.duolingo.com",
            "https://www.coursera.org",
            "https://medium.com/topic/self-improvement"
        )
        val url = productiveUrls.random()
        
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }

        // Start a 1-minute timer that continues even if they leave
        cooldownTimer = object : android.os.CountDownTimer(60 * 1000L, 1000) {
            override fun onTick(remaining: Long) {
                val secs = (remaining / 1000).toInt()
                tvTaskProgress.text = "Stay on article: $secs s left"
                pbTask.progress = 60 - secs
            }

            override fun onFinish() {
                tvTaskProgress.text = "Reading Completed!"
                pbTask.progress = 60
                onTaskCompleted()
            }
        }.start()
    }
    private fun onTaskCompleted() {
        blockedPackage?.let { pkg ->
            val durationMins = sharedPrefs.getLong("unlock_duration_mins", 15)
            val unlockUntil = System.currentTimeMillis() + (durationMins * 60 * 1000L)
            sharedPrefs.edit().putLong("unlock_$pkg", unlockUntil).apply()
        }

        tvTaskTitle.text = "✅ Unlocked!"
        tvTaskInstruction.text = "You've earned 15 minutes of access. Use it wisely!"
        tvTaskProgress.visibility = View.VISIBLE
        tvTaskProgress.text = "🎉"
        tvTaskProgress.textSize = 64f
        pbTask.visibility = View.GONE
        layoutMathProblem.visibility = View.GONE
        layoutTypePhrase.visibility = View.GONE

        // Auto-finish and show dashboard after a short delay
        tvTaskProgress.postDelayed({ 
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish() 
        }, 3000)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        cooldownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        cooldownTimer?.cancel()
    }
}
