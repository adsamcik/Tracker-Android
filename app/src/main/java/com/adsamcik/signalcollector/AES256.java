package com.adsamcik.signalcollector;

import android.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidAlgorithmParameterException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;

public class AES256 {
	private static final String ENCRYPTION_IV = "4e5Wa71fYoT7MFEX";
	private static final String ENCRYPTION_KEY = "AN5dJHque6mHeQf75REtYK3sK2SZDsCg";

	public static SecretKey generateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
		return new SecretKeySpec(ENCRYPTION_KEY.getBytes(), "AES");
	}

	public static String encryptMsg(String message, SecretKey secret) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidParameterSpecException, IllegalBlockSizeException, BadPaddingException, UnsupportedEncodingException {
		Cipher cipher = null;
		cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
		try {
			//cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8")));
			cipher.init(Cipher.ENCRYPT_MODE, secret);
			//Log.d("TAG", Base64.encodeToString(new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8")).getIV(), Base64.DEFAULT));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return Base64.encodeToString(cipher.doFinal(message.getBytes("UTF-8")), Base64.DEFAULT);
	}

	public static String decryptMsg(byte[] cipherText, SecretKey secret) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidParameterSpecException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException, UnsupportedEncodingException {
		Cipher cipher = null;
		cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, secret);
		return new String(cipher.doFinal(cipherText), "UTF-8");
	}

	static AlgorithmParameterSpec makeIv() {
		try {
			return new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}
}
