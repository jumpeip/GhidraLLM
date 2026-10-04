package ghidrallm.analysis;

import java.util.*;

import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.util.task.TaskMonitor;
import ghidrallm.config.AnalysisSettings;
import ghidrallm.config.AnalysisSettings.CodeMode;
import ghidrallm.config.AnalysisSettings.NeighborMode;

/**
 * Collects the target function's code, its variables, referenced strings and a bounded
 * caller/callee neighbourhood (breadth-first, depth- and size-limited).
 */
public class ContextBuilder {

	private final AnalysisSettings s;
	private final CodeExtractor ex;

	public ContextBuilder(AnalysisSettings settings, CodeExtractor extractor) {
		this.s = settings;
		this.ex = extractor;
	}

	public FunctionContext build(Function f, TaskMonitor monitor) {
		return build(f, monitor, s.maxContextChars);
	}

	public FunctionContext build(Function f, TaskMonitor monitor, long contextBudget) {
		FunctionContext c = new FunctionContext();
		c.function = f;
		c.name = f.getName();
		c.address = f.getEntryPoint().toString();
		c.signature = CodeExtractor.signature(f);
		c.callingConvention = f.getCallingConventionName();

		// Always decompile: the HighFunction is needed to rename parameters/locals even when
		// only disassembly is sent.
		CodeExtractor.Decompiled d = ex.decompile(f, monitor);
		c.high = d.high();
		c.decompileError = d.error();
		if (s.codeMode != CodeMode.DISASSEMBLY && d.ok()) {
			c.decompiled = CodeExtractor.truncate(d.cCode(),
				s.codeMode == CodeMode.BOTH ? s.maxCodeChars / 2 : s.maxCodeChars);
		}
		if (s.codeMode != CodeMode.DECOMPILED || !d.ok()) {
			int budget = s.codeMode == CodeMode.BOTH && c.decompiled != null
					? s.maxCodeChars - c.decompiled.length()
					: s.maxCodeChars;
			c.disassembly = ex.disassemble(f, Math.max(512, budget));
		}

		collectVariables(c, f);
		if (s.includeStrings) {
			c.strings.addAll(ex.referencedStrings(f, s.maxStrings));
		}
		if (s.includeImports) {
			for (Function callee : sorted(f.getCalledFunctions(monitor))) {
				if (callee.isExternal() || callee.isThunk()) {
					Function target = callee.isThunk() ? callee.getThunkedFunction(true) : callee;
					String nm = target != null ? target.getName(true) : callee.getName(true);
					if (!c.externalCalls.contains(nm)) {
						c.externalCalls.add(nm);
					}
				}
			}
		}

		long remaining = Math.max(0, contextBudget);
		remaining = walk(c, f, true, s.callerDepth, s.maxCallersPerLevel, remaining, monitor);
		walk(c, f, false, s.calleeDepth, s.maxCalleesPerLevel, remaining, monitor);
		return c;
	}

	private void collectVariables(FunctionContext c, Function f) {
		if (c.high != null) {
			LocalSymbolMap lsm = c.high.getLocalSymbolMap();
			List<HighSymbol> params = new ArrayList<>();
			List<HighSymbol> locals = new ArrayList<>();
			Iterator<HighSymbol> it = lsm.getSymbols();
			while (it.hasNext()) {
				HighSymbol hs = it.next();
				if (hs.isThisPointer() || hs.isHiddenReturn()) {
					continue;
				}
				(hs.isParameter() ? params : locals).add(hs);
			}
			params.sort(Comparator.comparingInt(HighSymbol::getCategoryIndex));
			locals.sort(Comparator.comparing(HighSymbol::getName));
			for (HighSymbol p : params) {
				c.variables.add(new FunctionContext.VarInfo(p.getName(), typeName(p),
					true, p.getCategoryIndex()));
			}
			for (HighSymbol l : locals) {
				c.variables.add(new FunctionContext.VarInfo(l.getName(), typeName(l), false, -1));
			}
			return;
		}
		// decompiler unavailable: fall back to the database view
		for (Parameter p : f.getParameters()) {
			c.variables.add(new FunctionContext.VarInfo(p.getName(),
				p.getDataType().getDisplayName(), true, p.getOrdinal()));
		}
		for (Variable v : f.getLocalVariables()) {
			c.variables.add(new FunctionContext.VarInfo(v.getName(),
				v.getDataType().getDisplayName(), false, -1));
		}
	}

	private static String typeName(HighSymbol hs) {
		try {
			return hs.getDataType() == null ? "?" : hs.getDataType().getDisplayName();
		}
		catch (Exception e) {
			return "?";
		}
	}

	/** Breadth-first walk of callers (up) or callees (down). Returns the remaining budget. */
	private long walk(FunctionContext c, Function root, boolean callers, int maxDepth,
			int perLevel, long budget, TaskMonitor monitor) {
		if (maxDepth <= 0 || perLevel <= 0) {
			return budget;
		}
		Set<Function> visited = new HashSet<>();
		visited.add(root);
		List<Function> frontier = List.of(root);
		List<FunctionContext.Neighbor> out = callers ? c.callers : c.callees;
		int omitted = 0;
		for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
			if (monitor != null && monitor.isCancelled()) {
				break;
			}
			LinkedHashSet<Function> next = new LinkedHashSet<>();
			for (Function f : frontier) {
				Set<Function> rel = callers ? f.getCallingFunctions(monitor)
						: f.getCalledFunctions(monitor);
				for (Function g : sorted(rel)) {
					if (visited.add(g)) {
						next.add(g);
					}
				}
			}
			List<Function> level = new ArrayList<>(next);
			if (level.size() > perLevel) {
				omitted += level.size() - perLevel;
				level = level.subList(0, perLevel);
			}
			for (Function g : level) {
				if (monitor != null && monitor.isCancelled()) {
					break;
				}
				String code = "";
				boolean external = g.isExternal() || g.isThunk();
				if (!external && budget > 200 && s.neighborMode != NeighborMode.SIGNATURE_ONLY) {
					int cap = (int) Math.min(s.maxNeighborChars, budget);
					code = neighborCode(g, cap, monitor);
				}
				FunctionContext.Neighbor n = new FunctionContext.Neighbor(
					callers ? "caller" : "callee", depth, g.getName(true),
					g.getEntryPoint().toString(), CodeExtractor.signature(g), code);
				long cost = n.signature().length() + code.length() + 80;
				if (cost > budget && !out.isEmpty()) {
					omitted++;
					continue;
				}
				out.add(n);
				budget -= cost;
				c.contextChars += cost;
			}
			frontier = level;
		}
		if (callers) {
			c.callersOmitted = omitted;
		}
		else {
			c.calleesOmitted = omitted;
		}
		return budget;
	}

	private String neighborCode(Function g, int cap, TaskMonitor monitor) {
		NeighborMode mode = s.neighborMode;
		if (mode == NeighborMode.SAME_AS_TARGET) {
			mode = s.codeMode == CodeMode.DISASSEMBLY ? NeighborMode.DISASSEMBLY
					: NeighborMode.DECOMPILED;
		}
		if (mode == NeighborMode.DECOMPILED) {
			CodeExtractor.Decompiled d = ex.decompile(g, monitor);
			if (d.ok()) {
				return CodeExtractor.truncate(d.cCode(), cap);
			}
		}
		return ex.disassemble(g, cap);
	}

	private static List<Function> sorted(Set<Function> set) {
		List<Function> l = new ArrayList<>(set);
		l.sort(Comparator.comparing(Function::getEntryPoint));
		return l;
	}
}
