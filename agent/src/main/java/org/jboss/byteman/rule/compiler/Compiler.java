/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009-10 Red Hat and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @authors Andrew Dinn
 */

package org.jboss.byteman.rule.compiler;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.binding.Binding;
import org.jboss.byteman.rule.binding.Bindings;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.agent.Transformer;
import org.jboss.byteman.rule.type.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.util.Iterator;

/**
 * A class which compiles a rule by generating a subclass of the rule's helperClass which implements
 * the HelperAdapter interface
 */
public class Compiler implements Opcodes
{
    public static String getHelperAdapterName(Class helperClass, boolean compileToBytecode)
    {
        String helperName = Type.getInternalName(helperClass);

        if (compileToBytecode) {
            return helperName + "_HelperAdapter_Compiled_" + nextId();
        } else {
            return helperName + "_HelperAdapter_Interpreted_" + nextId();
        }
    }

    public static Class getHelperAdapter(Rule rule, Class helperClass, String compiledHelperName, boolean compileToBytecode) throws CompileException
    {
        // ok we have to create the adapter class

        // n.b. we don't bother synchronizing here -- if another rule is racing to create an adapter
        // in parallel we don't really care about generating two of them -- we can use whichever
        // one gets installed last

        try {
            String helperName = Type.getInternalName(helperClass);
            byte[] classBytes = compileBytes(rule, helperClass, helperName, compiledHelperName, compileToBytecode);
            String externalName = compiledHelperName.replace('/', '.');
            // dump the compiled class bytes if required
            Transformer.maybeDumpClass(externalName, classBytes);
            // ensure the class is loaded
            // think we need to load the generated helper using the class loader of the trigger class
            ClassLoader loader = rule.getHelperLoader();
            return rule.getModuleSystem().loadHelperAdapter(loader, externalName, classBytes);
        } catch(CompileException ce) {
            throw ce;
        } catch (Throwable th) {
            if (compileToBytecode) {
                throw new CompileException("Compiler.createHelperAdapter : exception creating compiled helper adapter for " + helperClass.getName(), th);
            } else {
                throw new CompileException("Compiler.createHelperAdapter : exception creating interpreted helper adapter for " + helperClass.getName(), th);
            }
        }
    }

    private static byte[] compileBytes(Rule rule, Class helperClass, String helperName, String compiledHelperName, boolean compileToBytecode) throws Exception
    {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        FieldVisitor fv;
        MethodVisitor mv;
        CompileContext cc;

        // create the class as a subclass of the rule helper class, appending Compiled to the front
        // of the class name and a unique number to the end of the class helperName
        // also ensure it implements the HelperAdapter interface
        //
        // public class foo.bar.Compiled_<helper>_<NNN> extends foo.bar.<helper> implements HelperAdapter

        cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER, compiledHelperName, null, helperName, new String[] { "org/jboss/byteman/rule/helper/HelperAdapter" });
        // we need to install the source file name
        {
        String fullFileName = rule.getFile();
        int idx = fullFileName.lastIndexOf(java.io.File.separatorChar);
        String basicFileName = (idx < 0 ? fullFileName : fullFileName.substring(idx + 1));
        String debug = "// compiled from: " + fullFileName + "\n// generated by Byteman\n";
        cw.visitSource(basicFileName, debug);
        }
        {
            // create instance fields in the Helper for each input binding

            Bindings bindings = rule.getBindings();
            Iterator<Binding> iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                String name = binding.getIVarName();
                if(binding.isAlias()) {
                    // lookups and updates will use the aliased name
                    continue;
                }
                if(binding.isHelper()) {
                    // nothing to do
                } else {
                    // all other bindings need a field of the relevant type
                    org.jboss.byteman.rule.type.Type type = binding.getType();
                    if (rule.requiresAccess(type)) {
                        type = org.jboss.byteman.rule.type.Type.OBJECT;
                    }
                    fv = cw.visitField(ACC_PRIVATE, name, type.getInternalName(true, true), null, null);
                    fv.visitEnd();
                }
            }
        }
        {
        // and a rule field to hold the rule
        //
        // private Rule rule;

        fv = cw.visitField(ACC_PRIVATE, "rule", "Lorg/jboss/byteman/rule/Rule;", "Lorg/jboss/byteman/rule/Rule;", null);
        fv.visitEnd();
        }
        {
        // we need a constructor which takes a Rule as argument
        // if the helper implements a constructor which takes a Rule as argument then we invoke it
        // otherwise we invoke the empty helper constructor

        Constructor superConstructor = null;
        try {
            superConstructor = helperClass.getDeclaredConstructor(Rule.class);
        } catch (NoSuchMethodException e) {
            // hmm, ok see if there is an empty constructor
        } catch (SecurityException e) {
            throw new CompileException("Compiler.compileBytes : unable to access constructor for helper class " + helperClass.getCanonicalName());
        }
        boolean superWantsRule = (superConstructor != null);
        if (!superWantsRule) {
            try {
                superConstructor = helperClass.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new CompileException("Compiler.compileBytes : no valid constructor found for helper class " + helperClass.getCanonicalName());
            } catch (SecurityException e) {
                throw new CompileException("Compiler.compileBytes : unable to access constructor for helper class " + helperClass.getCanonicalName());
            }
        }
        //
        //  public Compiled<helper>_<NNN>()Rule rule)
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lorg/jboss/byteman/rule/Rule;)V", null, null);
        cc = new CompileContext(mv);
        cc.addLocalCount(2);
        mv.visitCode();
        // super();
        //
        // or
        //
        // super(Rule);
        if (superWantsRule) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            cc.addStackCount(2);
            mv.visitMethodInsn(INVOKESPECIAL, helperName, "<init>", "(Lorg/jboss/byteman/rule/Rule;)V");
            cc.addStackCount(-2);
        } else {
            mv.visitVarInsn(ALOAD, 0);
            cc.addStackCount(1);
            mv.visitMethodInsn(INVOKESPECIAL, helperName, "<init>", "()V");
            cc.addStackCount(-1);
        }
        // this.rule = rule
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        cc.addStackCount(2);
        mv.visitFieldInsn(PUTFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        cc.addStackCount(-2);
        // return;
        mv.visitInsn(RETURN);
        mv.visitMaxs(cc.getStackMax(), cc.getLocalMax());
        mv.visitEnd();
        }
        {
            // create the execute method
            //
            // public void execute(Bindings bindings, Object recipient, Object[] args) throws ExecuteException
            mv = cw.visitMethod(ACC_PUBLIC, "execute", "(Ljava/lang/Object;[Ljava/lang/Object;)V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            cc = new CompileContext(mv);
            cc.addLocalCount(3);
            mv.visitCode();
            // if (Transformer.isVerbose())
            mv.visitMethodInsn(INVOKESTATIC, "org/jboss/byteman/agent/Transformer", "isVerbose", "()Z");
            cc.addStackCount(1);
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            cc.addStackCount(-1);
            // then
            // System.out.println(rule.getName() + " execute");
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            cc.addStackCount(1);
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            cc.addStackCount(1);
            mv.visitInsn(DUP);
            cc.addStackCount(1);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V");
            cc.addStackCount(-1);
            mv.visitVarInsn(ALOAD, 0);
            cc.addStackCount(1);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getName", "()Ljava/lang/String;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
            cc.addStackCount(-1);
            mv.visitLdcInsn(" execute()");
            cc.addStackCount(1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
            cc.addStackCount(-1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");
            cc.addStackCount(-2);
            // end if
            if (cc.getStackCount() != 0) {
                throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
            }
            mv.visitLabel(l0);

            Bindings bindings = rule.getBindings();
            Iterator<Binding> iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                if (binding.isAlias()) {
                    // lookups and updates will use the aliased name
                    continue;
                }
                String name = binding.getIVarName();
                if (binding.isHelper()) {
                    // nothing to do
                } else if (binding.isRecipient()) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    cc.addStackCount(2);
                    org.jboss.byteman.rule.type.Type type = binding.getType();
                    if (rule.requiresAccess(type)) {
                        // treat inaccessible classes generically
                        type = org.jboss.byteman.rule.type.Type.OBJECT;
                    } else {
                        cc.compileTypeConversion(org.jboss.byteman.rule.type.Type.OBJECT, type);
                    }
                    mv.visitFieldInsn(PUTFIELD, compiledHelperName, name, type.getInternalName(true, true));
                    cc.addStackCount(-2);
                // } else if (binding.isParam() || binding.isLocalVar() || binding.isReturn() ||
                //             binding.isThrowable() || binding.isParamCount() || binding.isParamArray()) {
                } else if (!binding.isBindVar()) {
                    // refer to local vars using dollar prefix
                    // bindingMap.put(name, args[binding.getCallArrayIndex()]);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitLdcInsn(binding.getCallArrayIndex());
                    cc.addStackCount(3);
                    mv.visitInsn(AALOAD);
                    cc.addStackCount(-1);
                    org.jboss.byteman.rule.type.Type type = binding.getType();
                    if (rule.requiresAccess(type)) {
                        // treat inaccessible classes generically
                        type = org.jboss.byteman.rule.type.Type.OBJECT;
                    } else {
                        cc.compileTypeConversion(org.jboss.byteman.rule.type.Type.OBJECT, type);
                    }
                    mv.visitFieldInsn(PUTFIELD, compiledHelperName, name, type.getInternalName(true, true));
                    if (type.getNBytes() > 4) {
                        cc.addStackCount(-3);
                    } else {
                        cc.addStackCount(-2);
                    }
                }
                if (cc.getStackCount() != 0) {
                    throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
                }
            }
            // execute0()
            mv.visitVarInsn(ALOAD, 0);
            cc.addStackCount(1);
            mv.visitMethodInsn(INVOKEVIRTUAL, compiledHelperName, "execute0", "()V");
            cc.addStackCount(-1);

            // now restore update bindings

            iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                if (binding.isAlias()) {
                    continue;
                }
                String name = binding.getIVarName();

                if (binding.isUpdated()) {
                    // if (binding.isParam() || binding.isLocalVar() || binding.isReturn()) {
                    if (!binding.isBindVar()) {
                        // refer to local vars using dollar prefix
                        int idx = binding.getCallArrayIndex();
                        // Object value = bindingMap.get(name);
                        // args[idx] = value;
                        mv.visitVarInsn(ALOAD, 2); // args
                        mv.visitLdcInsn(idx);
                        mv.visitVarInsn(ALOAD, 0);
                        cc.addStackCount(3);
                        org.jboss.byteman.rule.type.Type type = binding.getType();
                        if (rule.requiresAccess(type)) {
                            // treat inaccessible classes generically
                            type = org.jboss.byteman.rule.type.Type.OBJECT;
                        }
                        mv.visitFieldInsn(GETFIELD, compiledHelperName, name, type.getInternalName(true, true));
                        if (type.getNBytes() > 4) {
                            cc.addStackCount(1);
                        }
                        cc.compileTypeConversion(type, org.jboss.byteman.rule.type.Type.OBJECT);
                        mv.visitInsn(AASTORE);
                        cc.addStackCount(-3);
                    }
                }
                if (cc.getStackCount() != 0) {
                    throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
                }
            }
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(cc.getStackMax(), cc.getLocalMax());
            mv.visitEnd();
        }
        {
        // create the setBinding method
        //
        // public void setBinding(String name, Object value)
            mv = cw.visitMethod(ACC_PUBLIC, "setBinding", "(Ljava/lang/String;Ljava/lang/Object;)V", null, null);
            cc = new CompileContext(mv);
            cc.addLocalCount(3);
            mv.visitCode();

            Bindings bindings = rule.getBindings();
            Iterator<Binding> iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                if (binding.isAlias()) {
                    continue;
                }
                String name = binding.getName();
                String ivarname = binding.getIVarName();
                if(binding.isHelper() || binding.isAlias()) {
                    // nothing to do
                } else {
                    Label skip = new Label();
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 1);
                    cc.addStackCount(2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                    cc.addStackCount(-1);
                    mv.visitJumpInsn(IFEQ, skip);
                    cc.addStackCount(-1);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 2);
                    cc.addStackCount(2);
                    org.jboss.byteman.rule.type.Type type = binding.getType();
                    if (rule.requiresAccess(type)) {
                        // treat inaccessible classes generically
                        type = org.jboss.byteman.rule.type.Type.OBJECT;
                    } else {
                        cc.compileTypeConversion(org.jboss.byteman.rule.type.Type.OBJECT, type);
                    }
                    mv.visitFieldInsn(PUTFIELD, compiledHelperName, ivarname, type.getInternalName(true, true));
                    if (type.getNBytes() > 4) {
                        cc.addStackCount(-3);
                    } else {
                        cc.addStackCount(-2);
                    }
                    mv.visitLabel(skip);
                }
                if (cc.getStackCount() != 0) {
                    throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
                }
            }
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(cc.getStackMax(), cc.getLocalMax());
            mv.visitEnd();
        }
        {
            // create the getBinding method
            //
            // public Object getBinding(String name)
            mv = cw.visitMethod(ACC_PUBLIC, "getBinding", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
            cc = new CompileContext(mv);
            cc.addLocalCount(2);
            mv.visitCode();
            Bindings bindings = rule.getBindings();
            Iterator<Binding> iterator = bindings.iterator();

            while (iterator.hasNext()) {
                Binding binding = iterator.next();
                String name = binding.getName();
                String ivarname = binding.getIVarName();
                if(binding.isAlias()) {
                    // lookups and updates will use the aliased name
                    continue;
                }
                if(binding.isHelper()) {
                    Label skip = new Label();
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 1);
                    cc.addStackCount(2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                    cc.addStackCount(-1);
                    mv.visitJumpInsn(IFEQ, skip);
                    cc.addStackCount(-1);
                    mv.visitVarInsn(ALOAD, 0);
                    cc.addStackCount(1);
                    mv.visitInsn(ARETURN);
                    cc.addStackCount(-1);
                    mv.visitLabel(skip);
                } else {
                    Label skip = new Label();
                    mv.visitLdcInsn(name);
                    mv.visitVarInsn(ALOAD, 1);
                    cc.addStackCount(2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                    cc.addStackCount(-1);
                    mv.visitJumpInsn(IFEQ, skip);
                    cc.addStackCount(-1);
                    mv.visitVarInsn(ALOAD, 0);
                    cc.addStackCount(1);
                    org.jboss.byteman.rule.type.Type type =  binding.getType();
                    if (rule.requiresAccess(type)) {
                        // treat inaccessible classes generically
                        type = org.jboss.byteman.rule.type.Type.OBJECT;
                    }
                    mv.visitFieldInsn(GETFIELD, compiledHelperName, ivarname, type.getInternalName(true, true));
                    if (type.getNBytes() > 4) {
                        cc.addStackCount(1);
                    }
                    cc.compileTypeConversion(type, org.jboss.byteman.rule.type.Type.OBJECT);
                    mv.visitInsn(ARETURN);
                    cc.addStackCount(-1);
                    mv.visitLabel(skip);
                }
                if (cc.getStackCount() != 0) {
                    throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
                }
            }
            // return
            mv.visitInsn(ACONST_NULL);
            cc.addStackCount(1);
            mv.visitInsn(ARETURN);
            cc.addStackCount(-1);
            if (cc.getStackCount() != 0) {
                throw new RuntimeException("Compiler.compileBytes: unexpected stack count " + cc.getStackCount());
            }
            mv.visitMaxs(cc.getStackMax(), cc.getLocalMax());
            mv.visitEnd();
        }
        {
        // create the getName method
        //
        // public String getName()
        mv = cw.visitMethod(ACC_PUBLIC, "getName", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        // {TOS} <== rule.getName()
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getName", "()Ljava/lang/String;");
        // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        }
        // create the getAccessibleField method
        //
        // public Object getAccessibleField(Object owner, int fieldIndex)
        {
        mv = cw.visitMethod(ACC_PUBLIC, "getAccessibleField", "(Ljava/lang/Object;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        // {TOS} <== rule.getAccessibleField(owner, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getAccessibleField", "(Ljava/lang/Object;I)Ljava/lang/Object;");
        // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
        }

        // create the setAccessibleField method
        //
        // public void setAccessibleField(Object owner, Object value, int fieldIndex)
        // rule.setAccessibleField(owner, value, fieldIndex);
        {
        mv = cw.visitMethod(ACC_PUBLIC, "setAccessibleField", "(Ljava/lang/Object;Ljava/lang/Object;I)V", null, null);
        mv.visitCode();
        // rule.setAccessibleField(owner, value, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "setAccessibleField", "(Ljava/lang/Object;Ljava/lang/Object;I)V");
        // return
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
        }

        // create the invokeAccessibleMethod method
        //
        // public Object invokeAccessibleMethod(Object target, Object[] args, int methodIndex)
        // {TOS} <==  rule.invokeAccessibleMethod(target, args, methodIndex);
        {
        mv = cw.visitMethod(ACC_PUBLIC, "invokeAccessibleMethod", "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;", null, null);
        mv.visitCode();
        // rule.invokeAccessibleMethod(target, args, fieldIndex);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "invokeAccessibleMethod", "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;");
            // return {TOS}
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
        }
        if (compileToBytecode) {
            // we generate a single execute0 method if we want to run compiled and get
            // the event, condiiton and action to insert the relevant bytecode to implement
            // bind(), test() and fire()

            {
            // create the execute0() method
            //
            // private void execute0()
            mv = cw.visitMethod(ACC_PRIVATE, "execute0", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            cc = new CompileContext(mv);
            // make sure we set the first line number before generating any code
            cc.notifySourceLine(rule.getLine());
            cc.addLocalCount(3); // for this and 2 object args
            // bind();
            rule.getEvent().compile(mv, cc);
            // if (test())
            rule.getCondition().compile(mv, cc);
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            cc.addStackCount(-1);
            // then
            rule.getAction().compile(mv, cc);
            // fire();
            // end if
            mv.visitLabel(l0);
            // this will match the ENDRULE line
            cc.notifySourceEnd();
            // return
            mv.visitInsn(RETURN);
            // need to specify correct Maxs values
            mv.visitMaxs(cc.getStackMax(), cc.getLocalMax());
            mv.visitEnd();
            }
        } else {
            // we generate the following methods if we want to run interpreted
            {
            // create the execute0() method
            //
            // private void execute0()
            mv = cw.visitMethod(ACC_PRIVATE, "execute0", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // bind();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "bind", "()V");
            // if (test())
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "test", "()Z");
            Label l0 = new Label();
            mv.visitJumpInsn(IFEQ, l0);
            // then
            // fire();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, compiledHelperName, "fire", "()V");
            // end if
            mv.visitLabel(l0);
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            }
            {
            // create the bind method
            //
            // private void bind()
            mv = cw.visitMethod(ACC_PRIVATE, "bind", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // rule.getEvent().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getEvent", "()Lorg/jboss/byteman/rule/Event;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Event", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
            {
            // create the test method
            //
            // private boolean test()
            mv = cw.visitMethod(ACC_PRIVATE, "test", "()Z", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // {TOS} <== rule.getCondition().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getCondition", "()Lorg/jboss/byteman/rule/Condition;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Condition", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
            // unbox the returned Boolean
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z");
            // return {TOS}
            mv.visitInsn(IRETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
            {
            // create the fire method
            //
            // private void fire()
            mv = cw.visitMethod(ACC_PRIVATE, "fire", "()V", null, new String[] { "org/jboss/byteman/rule/exception/ExecuteException" });
            mv.visitCode();
            // rule.getAction().interpret(this);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, compiledHelperName, "rule", "Lorg/jboss/byteman/rule/Rule;");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Rule", "getAction", "()Lorg/jboss/byteman/rule/Action;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/jboss/byteman/rule/Action", "interpret", "(Lorg/jboss/byteman/rule/helper/HelperAdapter;)Ljava/lang/Object;");
            // return
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
            }
        }

        cw.visitEnd();

        return cw.toByteArray();
    }

    private static int nextId = 0;

    private static synchronized int nextId()
    {
        return ++nextId;
    }

}
