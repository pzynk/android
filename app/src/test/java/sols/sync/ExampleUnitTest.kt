package sols.sync

import org.junit.Test
import com.google.firebase.ai.type.numInt
import com.google.firebase.ai.type.numDouble
import com.google.firebase.ai.type.str
import com.google.firebase.ai.type.boolean

class ExampleUnitTest {
    @Test
    fun testReflection() {
        val s1 = numInt("desc")
        val s2 = numDouble("desc")
        val s3 = str("desc")
        val s4 = boolean("desc")

        val sb = java.lang.StringBuilder()
        sb.append("Successfully compiled package-level functions!\n")

        try {
            val frpClass = Class.forName("com.google.firebase.ai.type.FunctionResponsePart")
            sb.append("\nFunctionResponsePart constructors:\n")
            for (ctor in frpClass.declaredConstructors) {
                sb.append("  ").append(ctor.toString()).append("\n")
            }
            sb.append("\nFunctionResponsePart companion methods:\n")
            try {
                val compClass = Class.forName("com.google.firebase.ai.type.FunctionResponsePart\$Companion")
                for (m in compClass.methods) {
                    sb.append("  ").append(m.name).append("(").append(m.parameterTypes.joinToString { it.name }).append("): ").append(m.returnType.name).append("\n")
                }
            } catch (e: Exception) {
                sb.append("Companion error: ").append(e.message).append("\n")
            }
        } catch (e: Exception) {
            sb.append("Failed to load FunctionResponsePart: ").append(e.message).append("\n")
        }

        throw RuntimeException(sb.toString())
    }
}