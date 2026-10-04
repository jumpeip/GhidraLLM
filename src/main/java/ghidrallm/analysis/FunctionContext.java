package ghidrallm.analysis;

import java.util.*;

import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.HighFunction;

/** Everything gathered about one function before it is sent to the LLM. */
public class FunctionContext {

	public record VarInfo(String name, String type, boolean parameter, int index) {
	}

	public record Neighbor(String relation, int depth, String name, String address,
			String signature, String code) {
	}

	public Function function;
	public String name;
	public String address;
	public String signature;
	public String callingConvention;

	public String decompiled;
	public String disassembly;
	public String decompileError;
	public HighFunction high;

	public final List<VarInfo> variables = new ArrayList<>();
	public final List<String> strings = new ArrayList<>();
	public final List<String> externalCalls = new ArrayList<>();
	public final List<Neighbor> callers = new ArrayList<>();
	public final List<Neighbor> callees = new ArrayList<>();
	public int callersOmitted;
	public int calleesOmitted;
	public int contextChars;

	public Set<String> variableNames() {
		Set<String> s = new LinkedHashSet<>();
		for (VarInfo v : variables) {
			s.add(v.name());
		}
		return s;
	}
}
