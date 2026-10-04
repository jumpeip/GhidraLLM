package ghidrallm.analysis;

import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.symbol.*;
import ghidra.util.task.TaskMonitor;

/**
 * Produces decompiled C or disassembly text for functions. Owns a {@link DecompInterface}, so
 * each worker thread needs its own instance; call {@link #dispose()} when done.
 */
public class CodeExtractor implements AutoCloseable {

	public record Decompiled(String cCode, HighFunction high, String error) {
		public boolean ok() {
			return cCode != null;
		}
	}

	private final Program program;
	private final int timeoutSeconds;
	private DecompInterface decomp;
	private String initError;

	public CodeExtractor(Program program, int timeoutSeconds) {
		this.program = program;
		this.timeoutSeconds = Math.max(1, timeoutSeconds);
	}

	public Program getProgram() {
		return program;
	}

	private synchronized DecompInterface decompiler() {
		if (decomp == null && initError == null) {
			DecompInterface d = new DecompInterface();
			DecompileOptions opts = new DecompileOptions();
			opts.grabFromProgram(program);
			d.setOptions(opts);
			d.toggleCCode(true);
			d.toggleSyntaxTree(true);
			d.setSimplificationStyle("decompile");
			if (!d.openProgram(program)) {
				initError = "Decompiler failed to start: " + d.getLastMessage();
				d.dispose();
				return null;
			}
			decomp = d;
		}
		return decomp;
	}

	public Decompiled decompile(Function f, TaskMonitor monitor) {
		if (f == null || f.isExternal()) {
			return new Decompiled(null, null, "external function");
		}
		DecompInterface d = decompiler();
		if (d == null) {
			return new Decompiled(null, null, initError);
		}
		try {
			DecompileResults res =
				d.decompileFunction(f, timeoutSeconds, monitor == null ? TaskMonitor.DUMMY : monitor);
			if (res == null || !res.decompileCompleted() || res.getDecompiledFunction() == null) {
				String err = res == null ? "no result" : res.getErrorMessage();
				if (err == null || err.isBlank()) {
					err = "decompilation did not complete (timeout " + timeoutSeconds + "s?)";
				}
				return new Decompiled(null, res == null ? null : res.getHighFunction(), err.trim());
			}
			return new Decompiled(res.getDecompiledFunction().getC(), res.getHighFunction(), null);
		}
		catch (Exception e) {
			return new Decompiled(null, null, "decompiler error: " + e.getMessage());
		}
	}

	/** Listing-style disassembly: labels, address, instruction (with symbol names), EOL comments. */
	public String disassemble(Function f, int maxChars) {
		if (f == null || f.isExternal()) {
			return "";
		}
		Listing listing = program.getListing();
		SymbolTable st = program.getSymbolTable();
		AddressSetView body = f.getBody();
		StringBuilder sb = new StringBuilder();
		Address entry = f.getEntryPoint();
		InstructionIterator it = listing.getInstructions(body, true);
		while (it.hasNext()) {
			Instruction ins = it.next();
			Address a = ins.getAddress();
			if (!a.equals(entry)) {
				Symbol s = st.getPrimarySymbol(a);
				if (s != null && s.getSymbolType() == SymbolType.LABEL) {
					sb.append(s.getName()).append(":\n");
				}
			}
			sb.append("  ").append(a).append("  ");
			sb.append(CodeUnitFormat.DEFAULT.getRepresentationString(ins));
			String eol = ins.getComment(CommentType.EOL);
			if (eol != null && !eol.isBlank()) {
				sb.append("    ; ").append(eol.replace('\n', ' '));
			}
			sb.append('\n');
			if (sb.length() > maxChars) {
				break;
			}
		}
		return truncate(sb.toString(), maxChars);
	}

	public static String signature(Function f) {
		try {
			return f.getPrototypeString(false, true);
		}
		catch (Exception e) {
			return f.getName() + "(...)";
		}
	}

	public static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		if (max <= 0 || s.length() <= max) {
			return s;
		}
		int cut = s.lastIndexOf('\n', max);
		if (cut < max / 2) {
			cut = max;
		}
		return s.substring(0, cut) + "\n/* ... truncated (" + (s.length() - cut) +
			" more characters) ... */\n";
	}

	/** Strings referenced from the function body (deduplicated, in address order). */
	public List<String> referencedStrings(Function f, int maxStrings) {
		List<String> out = new ArrayList<>();
		if (f == null || f.isExternal() || maxStrings <= 0) {
			return out;
		}
		Set<String> seen = new LinkedHashSet<>();
		ReferenceManager rm = program.getReferenceManager();
		Listing listing = program.getListing();
		for (Address from : rm.getReferenceSourceIterator(f.getBody(), true)) {
			for (Reference r : rm.getReferencesFrom(from)) {
				if (!r.isMemoryReference()) {
					continue;
				}
				Data d = listing.getDataContaining(r.getToAddress());
				if (d == null) {
					continue;
				}
				if (d.hasStringValue()) {
					String v = d.getDefaultValueRepresentation();
					if (v != null && !v.isBlank()) {
						seen.add(truncate(v, 300).strip());
					}
				}
				else if (d.isPointer() && d.getValue() instanceof Address pa) {
					Data pd = listing.getDataAt(pa);
					if (pd != null && pd.hasStringValue()) {
						seen.add(truncate(pd.getDefaultValueRepresentation(), 300).strip());
					}
				}
				if (seen.size() >= maxStrings) {
					break;
				}
			}
			if (seen.size() >= maxStrings) {
				break;
			}
		}
		out.addAll(seen);
		return out;
	}

	@Override
	public void close() {
		dispose();
	}

	public synchronized void dispose() {
		if (decomp != null) {
			try {
				decomp.closeProgram();
			}
			catch (Exception e) {
				// ignore
			}
			decomp.dispose();
			decomp = null;
		}
	}
}
