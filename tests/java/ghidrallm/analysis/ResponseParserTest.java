package ghidrallm.analysis;

import static org.junit.Assert.*;

import org.junit.Test;

public class ResponseParserTest {

	@Test
	public void parsesPlainJson() {
		RenameSuggestion s = ResponseParser.parse(
			"{\"function_name\":\"parse_config\",\"summary\":\"Parses cfg\",\"confidence\":0.8," +
				"\"renames\":[{\"old\":\"param_1\",\"new\":\"path\"},{\"old\":\"local_10\",\"new\":\"fd\"}]}");
		assertEquals("parse_config", s.functionName);
		assertEquals("Parses cfg", s.summary);
		assertEquals(0.8, s.confidence, 1e-9);
		assertEquals("path", s.renames.get("param_1"));
		assertEquals("fd", s.renames.get("local_10"));
	}

	@Test
	public void stripsFencesThinkingAndProse() {
		String text = "<think>let me think {not json}</think>Sure! Here you go:\n```json\n" +
			"{\"function_name\": \"init_socket\", \"renames\": {\"param_1\": \"port\"}}\n```\nDone.";
		RenameSuggestion s = ResponseParser.parse(text);
		assertEquals("init_socket", s.functionName);
		assertEquals("port", s.renames.get("param_1"));
	}

	@Test
	public void handlesBracesInsideStrings() {
		RenameSuggestion s = ResponseParser.parse(
			"noise {\"function_name\":\"fmt\",\"summary\":\"prints {x} and }\",\"renames\":[]} tail");
		assertEquals("fmt", s.functionName);
		assertEquals("prints {x} and }", s.summary);
	}

	@Test
	public void lenientJsonWithTrailingCommaAndPercentConfidence() {
		RenameSuggestion s = ResponseParser.parse(
			"{function_name: 'decrypt_payload', confidence: 85, renames: [{old:'uVar1', new:'key_len'},],}");
		assertEquals("decrypt_payload", s.functionName);
		assertEquals(0.85, s.confidence, 1e-9);
		assertEquals("key_len", s.renames.get("uVar1"));
	}

	@Test
	public void sanitizesNames() {
		assertEquals("read_file", ResponseParser.sanitize("read file"));
		assertEquals("_3des_decrypt", ResponseParser.sanitize("3des-decrypt"));
		assertEquals("method", ResponseParser.sanitize("MyClass::method(int)"));
		assertEquals("int_", ResponseParser.sanitize("int"));
		assertNull(ResponseParser.sanitize("___"));
		assertEquals(64, ResponseParser.sanitize("a".repeat(200)).length());
	}

	@Test
	public void rejectsDefaultStyleNames() {
		RenameSuggestion s = ResponseParser.parse(
			"{\"function_name\":\"FUN_00401000\",\"renames\":[{\"old\":\"param_1\",\"new\":\"param_2\"}," +
				"{\"old\":\"local_8\",\"new\":\"local_8\"},{\"old\":\"iVar1\",\"new\":\"index\"}]}");
		assertNull(s.functionName);
		assertFalse(s.renames.containsKey("param_1"));
		assertFalse(s.renames.containsKey("local_8"));
		assertEquals("index", s.renames.get("iVar1"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void noJsonThrows() {
		ResponseParser.parse("I cannot determine the function name.");
	}

	@Test
	public void defaultVarPattern() {
		for (String n : new String[] { "param_1", "local_10", "uVar3", "pcVar12", "in_RAX",
			"extraout_EAX", "unaff_EBX", "auStack_28", "uStack_14", "local_res8", "stack0x00000004" }) {
			assertTrue(n, ResponseParser.DEFAULT_VAR.matcher(n).matches());
		}
		for (String n : new String[] { "buffer", "socket_fd", "count", "lpBuffer" }) {
			assertFalse(n, ResponseParser.DEFAULT_VAR.matcher(n).matches());
		}
	}
}
