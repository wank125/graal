package com.oracle.truffle.llvm.initialization;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.llvm.parser.LLVMParserResult;
import com.oracle.truffle.llvm.parser.LLVMParserRuntime;
import com.oracle.truffle.llvm.parser.model.symbols.globals.GlobalVariable;
import com.oracle.truffle.llvm.parser.nodes.LLVMSymbolReadResolver;
import com.oracle.truffle.llvm.runtime.GetStackSpaceFactory;
import com.oracle.truffle.llvm.runtime.datalayout.DataLayout;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobal;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemoryOpNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMHasDatalayoutNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMStatementNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMPointer;
import com.oracle.truffle.llvm.runtime.types.ArrayType;
import com.oracle.truffle.llvm.runtime.types.PointerType;
import com.oracle.truffle.llvm.runtime.types.StructureType;
import com.oracle.truffle.llvm.runtime.types.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Initializes the memory, allocated by {@link InitializeSymbolsNode}, for a module and protects the
 * read only section.
 *
 * @see InitializeSymbolsNode see Runner.InitializeModuleNode
 * @see InitializeExternalNode see Runner.InitializeOverwriteNode
 */
public final class InitializeGlobalNode extends LLVMNode implements LLVMHasDatalayoutNode {

    private final DataLayout dataLayout;

    @Child StaticInitsNode globalVarInit;
    @Child LLVMMemoryOpNode protectRoData;

    public InitializeGlobalNode(FrameDescriptor rootFrame, LLVMParserResult parserResult, String moduleName) {
        this.dataLayout = parserResult.getDataLayout();

        this.globalVarInit = createGlobalVariableInitializer(rootFrame, parserResult, moduleName);
        this.protectRoData = parserResult.getRuntime().getNodeFactory().createProtectGlobalsBlock();
    }

    public void execute(VirtualFrame frame, LLVMPointer roDataBase) {
        globalVarInit.execute(frame);
        if (roDataBase != null) {
            // TODO could be a compile-time check
            protectRoData.execute(roDataBase);
        }
    }

    @Override
    public DataLayout getDatalayout() {
        return dataLayout;
    }

    private static StaticInitsNode createGlobalVariableInitializer(FrameDescriptor rootFrame, LLVMParserResult parserResult, String moduleName) {
        LLVMParserRuntime runtime = parserResult.getRuntime();
        LLVMSymbolReadResolver symbolResolver = new LLVMSymbolReadResolver(runtime, rootFrame, GetStackSpaceFactory.createAllocaFactory(), parserResult.getDataLayout(), false);
        final List<LLVMStatementNode> globalNodes = new ArrayList<>();
        for (GlobalVariable global : parserResult.getDefinedGlobals()) {
            final LLVMStatementNode store = createGlobalInitialization(runtime, symbolResolver, global, parserResult.getDataLayout());
            if (store != null) {
                globalNodes.add(store);
            }
        }
        LLVMStatementNode[] initNodes = globalNodes.toArray(LLVMStatementNode.NO_STATEMENTS);
        return StaticInitsNodeGen.create(initNodes, "global variable initializers", moduleName);
    }

    private static LLVMStatementNode createGlobalInitialization(LLVMParserRuntime runtime, LLVMSymbolReadResolver symbolResolver, GlobalVariable global, DataLayout dataLayout) {
        if (global == null || global.getValue() == null) {
            return null;
        }

        LLVMExpressionNode constant = symbolResolver.resolve(global.getValue());
        if (constant != null) {
            try {
                final Type type = global.getType().getPointeeType();
                final long size = type.getSize(dataLayout);

                /*
                 * For fetching the address of the global that we want to initialize, we must use
                 * the file scope because we are initializing the globals of the current file.
                 */
                LLVMGlobal globalDescriptor = runtime.getFileScope().getGlobalVariable(global.getName());
                assert globalDescriptor != null;
                final LLVMExpressionNode globalVarAddress = runtime.getNodeFactory().createLiteral(globalDescriptor, new PointerType(global.getType()));
                if (size != 0) {
                    if (type instanceof ArrayType || type instanceof StructureType) {
                        return runtime.getNodeFactory().createStore(globalVarAddress, constant, type);
                    } else {
                        Type t = global.getValue().getType();
                        return runtime.getNodeFactory().createStore(globalVarAddress, constant, t);
                    }
                }
            } catch (Type.TypeOverflowException e) {
                return Type.handleOverflowStatement(e);
            }
        }

        return null;
    }
}
