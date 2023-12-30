package net.weavemc.util.mappings

import com.grappenmaker.mappings.LambdaAwareMethodRemapper
import com.grappenmaker.mappings.LambdaAwareRemapper
import com.grappenmaker.mappings.Mappings
import com.grappenmaker.mappings.MappingsRemapper
import org.objectweb.asm.*
import org.objectweb.asm.commons.AnnotationRemapper
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

private data class MethodDeclaration(
    val owner: String,
    val name: String,
    val descriptor: String,
    val returnType: Type,
    val parameterTypes: List<Type>
)

private data class FieldDeclaration(
    val owner: String,
    val name: String
)

public open class ModJarRemapper(
    parent: ClassVisitor,
    remapper: Remapper
) : ClassRemapper(Opcodes.ASM9, parent, remapper) {
    private var originalTarget: String? = null

    override fun createAnnotationRemapper(
        descriptor: String?,
        parent: AnnotationVisitor?
    ): AnnotationVisitor {
        if (descriptor != "Lnet/weavemc/api/mixin/Mixin;")
            return super.createAnnotationRemapper(descriptor, parent)

        return object : AnnotationVisitor(Opcodes.ASM9, parent) {
            override fun visit(name: String, value: Any?) {
                val newValue = if (name == "targetClass") {
                    val original = (value as Type).internalName
                    originalTarget = original
                    Type.getObjectType(remapper.map(original))
                } else value

                super.visit(name, newValue)
            }
        }
    }

    override fun createMethodRemapper(parent: MethodVisitor): MethodVisitor {
        return ModMethodRemapper(originalTarget ?: return super.createMethodRemapper(parent), parent, remapper)
    }
}

public open class ModMethodRemapper(
    private val targetMixinClass: String,
    private val parent: MethodVisitor,
    remapper: Remapper
) : LambdaAwareMethodRemapper(parent, remapper) {
    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        val visitor = parent.visitAnnotation(descriptor, visible)
        return if (visitor != null) MixinAnnotationRemapper(targetMixinClass, visitor, descriptor, remapper)
        else super.visitAnnotation(descriptor, visible)
    }
}

public class MixinAnnotationRemapper(
    private val targetMixinClass: String,
    private val parent: AnnotationVisitor,
    descriptor: String?,
    remapper: Remapper
) : AnnotationRemapper(Opcodes.ASM9, descriptor, parent, remapper) {
    override fun visit(name: String, value: Any) {
        val remappedValue = when {
            value is String && shouldRemap() -> {
                val annotationName = descriptor.substringAfterLast('/')
                when (name) {
                    "method" -> {
                        if (!value.isMethod())
                            error("Failed to identify $value as a method in $annotationName annotation")

                        val method = parseMethod(targetMixinClass, value)
                            ?: error("Failed to parse mixin method value $value of annotation $annotationName")

                        val mappedName = remapper.mapMethodName(method.owner, method.name, method.descriptor)
                        val mappedDesc = remapper.mapMethodDesc(method.descriptor)
                        println("Mapping $method which resulted in $mappedName $mappedDesc")
                        mappedName + mappedDesc
                    }

                    "field" -> {
                        if (value.isMethod())
                            error("Identified $value as a method when field is expected in $annotationName annotation")

                        remapper.mapFieldName(targetMixinClass, value, null)
                    }

                    "target" -> {
                        name.parseAndRemapTarget()
                    }

                    else -> value
                }
            }

            else -> value
        }
        parent.visit(name, remappedValue)
    }

    private fun String.isMethod(): Boolean {
        val startIndex = this.indexOf('(')
        val endIndex = this.indexOf(')')
        return startIndex != -1 && endIndex != -1 && startIndex < endIndex
    }

    private fun String.parseAndRemapTarget(): String {
        return if (this.isMethod()) {
            val method = parseTargetMethod(this) ?: return this
            val mappedName = remapper.mapMethodName(method.owner, method.name, method.descriptor)
            val mappedDesc = remapper.mapMethodDesc(method.descriptor)
            mappedName + mappedDesc
        } else {
            val field = parseTargetField(this) ?: return this
            remapper.mapFieldName(field.owner, field.name, null)
        }
    }

    private fun shouldRemap(): Boolean =
        descriptor != null && descriptor.startsWith("Lnet/weavemc/api/mixin")
}

/**
 * Remaps a .jar file [input] to an [output] file, using [mappings], between namespaces [from] and [to].
 * If inheritance info from classpath jars matters, you should pass all of the relevant [classpath] jars.
 */
public fun remapJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    classpath: List<File> = listOf(),
) {
    val cache = hashMapOf<String, ByteArray?>()
    val jarsToUse = (classpath + input).map { JarFile(it) }
    val lookup = jarsToUse.flatMap { j ->
        j.entries().asSequence().filter { it.name.endsWith(".class") }
            .map { it.name.dropLast(6) to { j.getInputStream(it).readBytes() } }
    }.toMap()

    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream()).use { out ->
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            val remapper = MappingsRemapper(
                mappings, from, to,
                loader = { name -> if (name in lookup) cache.getOrPut(name) { lookup.getValue(name)() } else null }
            )

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)
                reader.accept(LambdaAwareRemapper(writer, remapper), 0)

                write("${remapper.map(reader.className)}.class", writer.toByteArray())
            }
        }
    }

    jarsToUse.forEach { it.close() }
}

public fun remapModJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    lookup: (String) -> ByteArray?,
) {
    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream()).use { out ->
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            val remapper = MappingsRemapper(
                mappings, from, to,
                loader = lookup
            )

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)

                reader.accept(ModJarRemapper(writer, remapper), 0)

                write("${remapper.map(reader.className)}.class", writer.toByteArray())
            }
        }
    }
}

public fun ByteArray.remap(remapper: Remapper): ByteArray {
    val reader = ClassReader(this)

    val writer = ClassWriter(reader, 0)
    reader.accept(LambdaAwareRemapper(writer, remapper), 0)

    return writer.toByteArray()
}

/**
 * Parses a method with an already known owner
 * @param owner The inferred owner
 * @param methodDeclaration The method declaration in methodName(methodDescriptor)methodReturnType format
 * @return MethodDeclaration data class including the parsed method name, descriptor, and the passed owner param
 */
private fun parseMethod(owner: String, methodDeclaration: String): MethodDeclaration? {
    try {
        val methodName = methodDeclaration.substringBefore('(')
        val methodDesc = methodDeclaration.drop(methodName.length)
        val methodType = Type.getMethodType(methodDesc)
        return MethodDeclaration(
            owner,
            methodName,
            methodDesc,
            methodType.returnType,
            methodType.argumentTypes.toList()
        )
    } catch (ex: Exception) {
        println("Failed to parse method declaration: $methodDeclaration")
        ex.printStackTrace()
    }
    return null
}

private fun parseTargetField(fieldDeclaration: String): FieldDeclaration? {
    try {
        val (classPath, fieldName) = fieldDeclaration.splitAround('.')
        return FieldDeclaration(classPath, fieldName)
    } catch (ex: Exception) {
        println("Failed to parse field declaration: $fieldDeclaration")
        ex.printStackTrace()
    }
    return null
}

/**
 * Parses a method without an already known owner.
 * Typically used for Mixin @At target values, where the owner cannot be inferred.
 * @param methodDeclaration The method declaration in methodOwner.methodName(methodDescriptor)methodReturnType format
 * @return MethodDeclaration data class including the parsed method name, descriptor, and owner
 */
private fun parseTargetMethod(methodDeclaration: String): MethodDeclaration? {
    try {
        val (classPath, fullMethod) = methodDeclaration.splitAround('.')
        val methodName = fullMethod.substringBefore('(')
        val methodDesc = fullMethod.drop(methodName.length)
        val methodType = Type.getMethodType(methodDesc)
        return MethodDeclaration(
            classPath,
            methodName,
            methodDesc,
            methodType.returnType,
            methodType.argumentTypes.toList()
        )
    } catch (ex: Exception) {
        println("Failed to parse method declaration: $methodDeclaration")
        ex.printStackTrace()
    }
    return null
}
