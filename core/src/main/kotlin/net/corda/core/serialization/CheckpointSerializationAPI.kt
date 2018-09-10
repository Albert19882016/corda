package net.corda.core.serialization

import net.corda.core.DoNotImplement
import net.corda.core.KeepForDJVM
import net.corda.core.crypto.SecureHash
import net.corda.core.serialization.internal.effectiveSerializationEnv
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.sequence

/**
 * An abstraction for serializing and deserializing objects at checkpoints, using Kryo serialization.
 */
abstract class CheckpointSerializationFactory {
    /**
     * Deserialize the bytes in to an object, using the prefixed bytes to determine the format.
     *
     * @param byteSequence The bytes to deserialize, including a format header prefix.
     * @param clazz The class or superclass or the object to be deserialized, or [Any] or [Object] if unknown.
     * @param context A context that configures various parameters to deserialization.
     */
    abstract fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: CheckpointSerializationContext): T

    /**
     * Serialize an object to bytes using the preferred serialization format version from the context.
     *
     * @param obj The object to be serialized.
     * @param context A context that configures various parameters to serialization, including the serialization format version.
     */
    abstract fun <T : Any> serialize(obj: T, context: CheckpointSerializationContext): SerializedBytes<T>

    /**
     * If there is a need to nest serialization/deserialization with a modified context during serialization or deserialization,
     * this will return the current context used to start serialization/deserialization.
     */
    val currentContext: CheckpointSerializationContext? get() = _currentContext.get()

    /**
     * A context to use as a default if you do not require a specially configured context.  It will be the current context
     * if the use is somehow nested (see [currentContext]).
     */
    val defaultContext: CheckpointSerializationContext get() = currentContext ?: effectiveSerializationEnv.checkpointContext

    private val _currentContext = ThreadLocal<CheckpointSerializationContext?>()

    /**
     * Change the current context inside the block to that supplied.
     */
    fun <T> withCurrentContext(context: CheckpointSerializationContext?, block: () -> T): T {
        val priorContext = _currentContext.get()
        if (context != null) _currentContext.set(context)
        try {
            return block()
        } finally {
            if (context != null) _currentContext.set(priorContext)
        }
    }

    /**
     * Allow subclasses to temporarily mark themselves as the current factory for the current thread during serialization/deserialization.
     * Will restore the prior context on exiting the block.
     */
    fun <T> asCurrent(block: CheckpointSerializationFactory.() -> T): T {
        val priorContext = _currentFactory.get()
        _currentFactory.set(this)
        try {
            return this.block()
        } finally {
            _currentFactory.set(priorContext)
        }
    }

    companion object {
        private val _currentFactory = ThreadLocal<CheckpointSerializationFactory?>()

        /**
         * A default factory for serialization/deserialization, taking into account the [currentFactory] if set.
         */
        val defaultFactory: CheckpointSerializationFactory get() = currentFactory ?: effectiveSerializationEnv.checkpointSerializationFactory

        /**
         * If there is a need to nest serialization/deserialization with a modified context during serialization or deserialization,
         * this will return the current factory used to start serialization/deserialization.
         */
        val currentFactory: CheckpointSerializationFactory? get() = _currentFactory.get()
    }
}

/**
 * Parameters to checkpoint serialization and deserialization.
 */
@KeepForDJVM
@DoNotImplement
interface CheckpointSerializationContext {
    /**
     * If non-null, apply this encoding (typically compression) when serializing.
     */
    val encoding: SerializationEncoding?
    /**
     * The class loader to use for deserialization.
     */
    val deserializationClassLoader: ClassLoader
    /**
     * A whitelist that contains (mostly for security purposes) which classes can be serialized and deserialized.
     */
    val whitelist: ClassWhitelist
    /**
     * A whitelist that determines (mostly for security purposes) whether a particular encoding may be used when deserializing.
     */
    val encodingWhitelist: EncodingWhitelist
    /**
     * A map of any addition properties specific to the particular use case.
     */
    val properties: Map<Any, Any>
    /**
     * Duplicate references to the same object preserved in the wire format and when deserialized when this is true,
     * otherwise they appear as new copies of the object.
     */
    val objectReferencesEnabled: Boolean

    /**
     * Helper method to return a new context based on this context with the property added.
     */
    fun withProperty(property: Any, value: Any): CheckpointSerializationContext

    /**
     * Helper method to return a new context based on this context with object references disabled.
     */
    fun withoutReferences(): CheckpointSerializationContext

    /**
     * Helper method to return a new context based on this context with the deserialization class loader changed.
     */
    fun withClassLoader(classLoader: ClassLoader): CheckpointSerializationContext

    /**
     * Helper method to return a new context based on this context with the appropriate class loader constructed from the passed attachment identifiers.
     * (Requires the attachment storage to have been enabled).
     */
    @Throws(MissingAttachmentsException::class)
    fun withAttachmentsClassLoader(attachmentHashes: List<SecureHash>): CheckpointSerializationContext

    /**
     * Helper method to return a new context based on this context with the given class specifically whitelisted.
     */
    fun withWhitelisted(clazz: Class<*>): CheckpointSerializationContext

    /**
     * A shallow copy of this context but with the given (possibly null) encoding.
     */
    fun withEncoding(encoding: SerializationEncoding?): CheckpointSerializationContext

    /**
     * A shallow copy of this context but with the given encoding whitelist.
     */
    fun withEncodingWhitelist(encodingWhitelist: EncodingWhitelist): CheckpointSerializationContext
}

/*
 * The following extension methods are disambiguated from the AMQP-serialization methods by requiring that an
 * explicit [CheckpointSerializationContext] parameter be provided.
 */

/*
 * Convenience extension method for deserializing a ByteSequence, utilising the default factory.
 */
inline fun <reified T : Any> ByteSequence.checkpointDeserialize(serializationFactory: CheckpointSerializationFactory = CheckpointSerializationFactory.defaultFactory,
                                                                context: CheckpointSerializationContext): T {
    return serializationFactory.deserialize(this, T::class.java, context)
}

/**
 * Convenience extension method for deserializing SerializedBytes with type matching, utilising the default factory.
 */
inline fun <reified T : Any> SerializedBytes<T>.checkpointDeserialize(serializationFactory: CheckpointSerializationFactory = CheckpointSerializationFactory.defaultFactory,
                                                                      context: CheckpointSerializationContext): T {
    return serializationFactory.deserialize(this, T::class.java, context)
}

/**
 * Convenience extension method for deserializing a ByteArray, utilising the default factory.
 */
inline fun <reified T : Any> ByteArray.checkpointDeserialize(serializationFactory: CheckpointSerializationFactory = CheckpointSerializationFactory.defaultFactory,
                                                             context: CheckpointSerializationContext): T {
    require(isNotEmpty()) { "Empty bytes" }
    return this.sequence().checkpointDeserialize(serializationFactory, context)
}

/**
 * Convenience extension method for serializing an object of type T, utilising the default factory.
 */
fun <T : Any> T.checkpointSerialize(serializationFactory: CheckpointSerializationFactory = CheckpointSerializationFactory.defaultFactory,
                                    context: CheckpointSerializationContext): SerializedBytes<T> {
    return serializationFactory.serialize(this, context)
}