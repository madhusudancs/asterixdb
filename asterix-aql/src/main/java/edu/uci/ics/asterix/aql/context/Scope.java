package edu.uci.ics.asterix.aql.context;

import java.util.HashMap;

import edu.uci.ics.asterix.aql.expression.Identifier;
import edu.uci.ics.asterix.aql.expression.VarIdentifier;
import edu.uci.ics.asterix.aql.parser.ScopeChecker;
import edu.uci.ics.asterix.common.functions.FunctionSignature;

public final class Scope {
    private Scope parent;
    private HashMap<String, Identifier> symbols = null;
    private FunctionSignatures functionSignatures = null;
    private final ScopeChecker scopeChecker;
    private boolean maskParentScope = false;

    public Scope(ScopeChecker scopeChecker, Scope parent) {
        this.scopeChecker = scopeChecker;
        this.parent = parent;
    }

    public Scope(ScopeChecker scopeChecker) {
        this(scopeChecker, null);
    }

    public Scope(ScopeChecker scopeChecker, Scope parent, boolean maskParentScope) {
        this(scopeChecker, parent);
        this.maskParentScope = maskParentScope;
    }

    /**
     * Find a symbol in the scope
     * 
     * @param name
     * @return the Identifier of this symbol; otherwise null;
     */
    public Identifier findSymbol(String name) {
        Identifier ident = null;
        if (symbols != null) {
            ident = symbols.get(name);
        }
        if (ident == null && !maskParentScope && parent != null) {
            ident = parent.findSymbol(name);
        }
        return ident;
    }

    public Identifier findLocalSymbol(String name) {
        if (symbols != null) {
            return symbols.get(name);
        }
        return null;
    }

    /**
     * Add a symbol into scope
     * 
     * @param ident
     */
    public void addSymbolToScope(Identifier ident) {
        if (symbols == null) {
            symbols = new HashMap<String, Identifier>();
        }
        symbols.put(ident.getValue(), ident);
    }

    public void addNewVarSymbolToScope(VarIdentifier ident) {
        scopeChecker.incVarCounter();
        ident.setId(scopeChecker.getVarCounter());
        addSymbolToScope(ident);
    }

    /**
     * Add a FunctionDescriptor into functionSignatures
     * 
     * @param fd
     *            FunctionDescriptor
     * @param varargs
     *            whether this function has varargs
     */
    public void addFunctionDescriptor(FunctionSignature signature, boolean varargs) {
        if (functionSignatures == null) {
            functionSignatures = new FunctionSignatures();
        }
        functionSignatures.put(signature, varargs);
    }

    /**
     * find a function signature
     * 
     * @param name
     *            name of the function
     * @param arity
     *            # of arguments
     * @return FunctionDescriptor of the function found; otherwise null
     */
    public FunctionSignature findFunctionSignature(String dataverse, String name, int arity) {
        FunctionSignature fd = null;
        if (functionSignatures != null) {
            fd = functionSignatures.get(dataverse, name, arity);
        }
        if (fd == null && parent != null) {
            fd = parent.findFunctionSignature(dataverse, name, arity);
        }
        return fd;
    }

}
