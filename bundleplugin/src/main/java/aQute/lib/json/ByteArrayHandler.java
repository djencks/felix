package aQute.lib.json;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import aQute.lib.base64.*;
import aQute.lib.hex.*;

public class ByteArrayHandler extends Handler {

	@Override
	void encode(Encoder app, Object object, Map<Object,Type> visited) throws IOException, Exception {
		if ( app.codec.isHex())
			StringHandler.string(app, Hex.toHexString((byte[]) object));
		else
			StringHandler.string(app, Base64.encodeBase64((byte[]) object));
	}

	@Override
	Object decodeArray(Decoder r) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		ArrayList<Object> list = new ArrayList<Object>();
		r.codec.parseArray(list, Byte.class, r);
		for (Object b : list) {
			out.write(((Byte) b).byteValue());
		}
		return out.toByteArray();
	}

	@Override
	Object decode(Decoder dec, String s) throws Exception {
		if ( dec.codec.isHex())
			return Hex.toByteArray(s);
		else
			return Base64.decodeBase64(s);
	}
}
