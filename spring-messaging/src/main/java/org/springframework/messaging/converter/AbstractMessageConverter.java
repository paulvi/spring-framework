/*
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.messaging.converter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;

/**
 * Abstract base class for {@link MessageConverter} implementations including support
 * for common properties and a partial implementation of the conversion methods,
 * mainly to check if the converter supports the conversion based on the payload class
 * and MIME type.
 *
 * @author Rossen Stoyanchev
 * @author Sebastien Deleuze
 * @since 4.0
 */
public abstract class AbstractMessageConverter implements MessageConverter {

	/**
	 * Name of the header that can be set to provide further information
	 * ({@link MethodParameter} instance) about the origin of the payload (for
	 * {@link #toMessage(Object, MessageHeaders)}) or about the target of the payload
	 * ({@link #fromMessage(Message, Class)}).
	 * @since 4.2
	 */
	public static final String METHOD_PARAMETER_HINT_HEADER = "methodParameterHint";


	protected final Log logger = LogFactory.getLog(getClass());

	private final List<MimeType> supportedMimeTypes;

	private ContentTypeResolver contentTypeResolver = new DefaultContentTypeResolver();

	private boolean strictContentTypeMatch = false;

	private Class<?> serializedPayloadClass = byte[].class;


	/**
	 * Construct an {@code AbstractMessageConverter} supporting a single MIME type.
	 * @param supportedMimeType the supported MIME type
	 */
	protected AbstractMessageConverter(MimeType supportedMimeType) {
		Assert.notNull(supportedMimeType, "supportedMimeType is required");
		this.supportedMimeTypes = Collections.<MimeType>singletonList(supportedMimeType);
	}

	/**
	 * Construct an {@code AbstractMessageConverter} supporting multiple MIME types.
	 * @param supportedMimeTypes the supported MIME types
	 */
	protected AbstractMessageConverter(Collection<MimeType> supportedMimeTypes) {
		Assert.notNull(supportedMimeTypes, "supportedMimeTypes must not be null");
		this.supportedMimeTypes = new ArrayList<MimeType>(supportedMimeTypes);
	}


	/**
	 * Return the supported MIME types.
	 */
	public List<MimeType> getSupportedMimeTypes() {
		return Collections.unmodifiableList(this.supportedMimeTypes);
	}

	/**
	 * Configure the {@link ContentTypeResolver} to use to resolve the content
	 * type of an input message.
	 * <p>Note that if no resolver is configured, then
	 * {@link #setStrictContentTypeMatch(boolean) strictContentTypeMatch} should
	 * be left as {@code false} (the default) or otherwise this converter will
	 * ignore all messages.
	 * <p>By default, a {@code DefaultContentTypeResolver} instance is used.
	 */
	public void setContentTypeResolver(ContentTypeResolver resolver) {
		this.contentTypeResolver = resolver;
	}

	/**
	 * Return the configured {@link ContentTypeResolver}.
	 */
	public ContentTypeResolver getContentTypeResolver() {
		return this.contentTypeResolver;
	}

	/**
	 * Whether this converter should convert messages for which no content type
	 * could be resolved through the configured
	 * {@link org.springframework.messaging.converter.ContentTypeResolver}.
	 * <p>A converter can configured to be strict only when a
	 * {@link #setContentTypeResolver contentTypeResolver} is configured and the
	 * list of {@link #getSupportedMimeTypes() supportedMimeTypes} is not be empty.
	 * <p>When this flag is set to {@code true}, {@link #supportsMimeType(MessageHeaders)}
	 * will return {@code false} if the {@link #setContentTypeResolver contentTypeResolver}
	 * is not defined or if no content-type header is present.
	 */
	public void setStrictContentTypeMatch(boolean strictContentTypeMatch) {
		if (strictContentTypeMatch) {
			Assert.notEmpty(getSupportedMimeTypes(), "Strict match requires non-empty list of supported mime types");
			Assert.notNull(getContentTypeResolver(), "Strict match requires ContentTypeResolver");
		}
		this.strictContentTypeMatch = strictContentTypeMatch;
	}

	/**
	 * Whether content type resolution must produce a value that matches one of
	 * the supported MIME types.
	 */
	public boolean isStrictContentTypeMatch() {
		return this.strictContentTypeMatch;
	}

	/**
	 * Configure the preferred serialization class to use (byte[] or String) when
	 * converting an Object payload to a {@link Message}.
	 * <p>The default value is byte[].
	 * @param payloadClass either byte[] or String
	 */
	public void setSerializedPayloadClass(Class<?> payloadClass) {
		Assert.isTrue(byte[].class == payloadClass || String.class == payloadClass,
				"Payload class must be byte[] or String: " + payloadClass);
		this.serializedPayloadClass = payloadClass;
	}

	/**
	 * Return the configured preferred serialization payload class.
	 */
	public Class<?> getSerializedPayloadClass() {
		return this.serializedPayloadClass;
	}


	/**
	 * Returns the default content type for the payload. Called when
	 * {@link #toMessage(Object, MessageHeaders)} is invoked without message headers or
	 * without a content type header.
	 * <p>By default, this returns the first element of the {@link #getSupportedMimeTypes()
	 * supportedMimeTypes}, if any. Can be overridden in sub-classes.
	 * @param payload the payload being converted to message
	 * @return the content type, or {@code null} if not known
	 */
	protected MimeType getDefaultContentType(Object payload) {
		List<MimeType> mimeTypes = getSupportedMimeTypes();
		return (!mimeTypes.isEmpty() ? mimeTypes.get(0) : null);
	}

	@Override
	public final Object fromMessage(Message<?> message, Class<?> targetClass) {
		if (!canConvertFrom(message, targetClass)) {
			return null;
		}
		return convertFromInternal(message, targetClass);
	}

	protected boolean canConvertFrom(Message<?> message, Class<?> targetClass) {
		return (supports(targetClass) && supportsMimeType(message.getHeaders()));
	}

	@Override
	public final Message<?> toMessage(Object payload, MessageHeaders headers) {
		if (!canConvertTo(payload, headers)) {
			return null;
		}

		payload = convertToInternal(payload, headers);
		MimeType mimeType = getDefaultContentType(payload);

		if (headers != null) {
			MessageHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(headers, MessageHeaderAccessor.class);
			if (accessor != null && accessor.isMutable()) {
				accessor.setHeaderIfAbsent(MessageHeaders.CONTENT_TYPE, mimeType);
				return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
			}
		}

		MessageBuilder<?> builder = MessageBuilder.withPayload(payload);
		if (headers != null) {
			builder.copyHeaders(headers);
		}
		builder.setHeaderIfAbsent(MessageHeaders.CONTENT_TYPE, mimeType);
		return builder.build();
	}

	protected boolean canConvertTo(Object payload, MessageHeaders headers) {
		Class<?> clazz = (payload != null ? payload.getClass() : null);
		return (supports(clazz) && supportsMimeType(headers));
	}

	protected boolean supportsMimeType(MessageHeaders headers) {
		if (getSupportedMimeTypes().isEmpty()) {
			return true;
		}
		MimeType mimeType = getMimeType(headers);
		if (mimeType == null) {
			return !isStrictContentTypeMatch();
		}
		for (MimeType current : getSupportedMimeTypes()) {
			if (current.getType().equals(mimeType.getType()) && current.getSubtype().equals(mimeType.getSubtype())) {
				return true;
			}
		}
		return false;
	}

	protected MimeType getMimeType(MessageHeaders headers) {
		return (this.contentTypeResolver != null ? this.contentTypeResolver.resolve(headers) : null);
	}


	/**
	 * Whether the given class is supported by this converter.
	 * @param clazz the class to test for support
	 * @return {@code true} if supported; {@code false} otherwise
	 */
	protected abstract boolean supports(Class<?> clazz);

	/**
	 * Convert the message payload from serialized form to an Object.
	 */
	public abstract Object convertFromInternal(Message<?> message, Class<?> targetClass);


	/**
	 * Convert the payload object to serialized form.
	 */
	public abstract Object convertToInternal(Object payload, MessageHeaders headers);

}
