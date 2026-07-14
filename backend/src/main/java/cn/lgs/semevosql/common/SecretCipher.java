/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AES-256-GCM credential encryption with plaintext-read compatibility for one-way
 * migration.
 */
@Component
public class SecretCipher {

	private static final String PREFIX = "enc:v1:";

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecureRandom secureRandom = new SecureRandom();

	private final SecretKeySpec key;

	@Autowired
	public SecretCipher(SecretEncryptionProperties properties) {
		this(properties.getEncryptionKey());
	}

	SecretCipher(String encodedKey) {
		if (!StringUtils.hasText(encodedKey)) {
			this.key = null;
			return;
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(encodedKey.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("semevosql.secrets.encryption-key must be Base64", ex);
		}
		if (decoded.length != 32) {
			throw new IllegalArgumentException("semevosql.secrets.encryption-key must decode to exactly 32 bytes");
		}
		this.key = new SecretKeySpec(decoded, "AES");
	}

	public boolean isEnabled() {
		return key != null;
	}

	public String encrypt(String plaintext) {
		return StringUtils.hasText(plaintext) && plaintext.startsWith(PREFIX) ? plaintext : encryptPlaintext(plaintext);
	}

	/**
	 * Encrypt a newly submitted plaintext credential, even when its literal value starts
	 * with the storage prefix.
	 */
	public String encryptPlaintext(String plaintext) {
		if (!StringUtils.hasText(plaintext) || key == null) {
			return plaintext;
		}
		try {
			byte[] nonce = new byte[NONCE_BYTES];
			secureRandom.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			ByteBuffer payload = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted);
			return PREFIX + Base64.getEncoder().encodeToString(payload.array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Credential encryption failed", ex);
		}
	}

	public String decrypt(String ciphertext) {
		if (!StringUtils.hasText(ciphertext) || !ciphertext.startsWith(PREFIX)) {
			return ciphertext;
		}
		if (key == null) {
			throw new IllegalStateException("Credential is encrypted but no SemEvoSQL encryption key is configured");
		}
		try {
			byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
			if (payload.length <= NONCE_BYTES) {
				throw new IllegalArgumentException("Encrypted credential payload is invalid");
			}
			ByteBuffer buffer = ByteBuffer.wrap(payload);
			byte[] nonce = new byte[NONCE_BYTES];
			buffer.get(nonce);
			byte[] encrypted = new byte[buffer.remaining()];
			buffer.get(encrypted);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("Credential decryption failed", ex);
		}
	}

	public String hint(String secret) {
		String plaintext = decrypt(secret);
		if (!StringUtils.hasText(plaintext)) {
			return null;
		}
		int visible = Math.min(4, plaintext.length());
		return "****" + plaintext.substring(plaintext.length() - visible);
	}

}
