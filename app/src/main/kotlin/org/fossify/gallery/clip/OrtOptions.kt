package org.fossify.gallery.clip

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

// Nastavenia ONNX Runtime (API 1.20) pre modely appky — menej vlákien a menej natívnej pamäte.
//  - vizuál (indexovanie fotiek, beží dlho na pozadí): 2 vlákna v operátore, 1 medzi operátormi,
//    postupné vykonávanie a bez „spinningu“ (vlákna nečakajú aktívne a nežerú CPU/batériu),
//  - text a multilingválny text (krátky dopyt pri hľadaní): 1 vlákno, bez CPU arény a bez
//    predalokácie podľa vzoru pamäte — po dopyte nedrží stovky MB rezervovanej pamäte.
// Volajúci ošetruje výnimky (vytvorenie sessie môže zlyhať, napr. poškodený model).
object OrtOptions {
    private const val VISUAL_INTRA_THREADS = 2
    private const val ALLOW_SPINNING = "session.intra_op.allow_spinning"

    fun createVisual(env: OrtEnvironment, file: File): OrtSession =
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(VISUAL_INTRA_THREADS)
            o.setInterOpNumThreads(1)
            o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            o.addConfigEntry(ALLOW_SPINNING, "0")
            env.createSession(file.absolutePath, o)
        }

    fun createText(env: OrtEnvironment, file: File): OrtSession =
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(1)
            o.setInterOpNumThreads(1)
            o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            o.addConfigEntry(ALLOW_SPINNING, "0")
            o.setCPUArenaAllocator(false)
            o.setMemoryPatternOptimization(false)
            env.createSession(file.absolutePath, o)
        }
}
