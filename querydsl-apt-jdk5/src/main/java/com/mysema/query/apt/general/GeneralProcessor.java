/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.apt.general;

import static com.mysema.query.apt.APTUtils.getString;
import static com.sun.mirror.util.DeclarationVisitors.NO_OP;
import static com.sun.mirror.util.DeclarationVisitors.getDeclarationScanner;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.mysema.query.codegen.ClassModelFactory;
import com.mysema.query.codegen.ClassModel;
import com.mysema.query.codegen.Serializer;
import com.mysema.query.codegen.Serializers;
import com.mysema.query.util.FileUtils;
import com.sun.mirror.apt.AnnotationProcessor;
import com.sun.mirror.apt.AnnotationProcessorEnvironment;
import com.sun.mirror.declaration.AnnotationTypeDeclaration;
import com.sun.mirror.declaration.Declaration;

/**
 * GeneralProcessor is the main processor for APT code generation.
 * 
 * @author tiwe
 * @version $Id$
 */
public abstract class GeneralProcessor implements AnnotationProcessor {

    protected final String namePrefix, targetFolder;

    protected final AnnotationProcessorEnvironment env;

    protected final String superClassAnnotation, domainAnnotation,
            dtoAnnotation;

    public GeneralProcessor(AnnotationProcessorEnvironment env,
            String superClassAnnotation, String domainAnnotation,
            String dtoAnnotation) {
        this.env = env;
        this.targetFolder = env.getOptions().get("-s");
        this.namePrefix = getString(env.getOptions(), "namePrefix", "Q");

        this.superClassAnnotation = superClassAnnotation;
        this.domainAnnotation = domainAnnotation;
        this.dtoAnnotation = dtoAnnotation;
    }

    private void addSupertypeFields(ClassModel typeDecl,
            Map<String, ClassModel> entityTypes, Map<String, ClassModel> mappedSupertypes) {
        String stype = typeDecl.getSupertypeName();
        Class<?> superClass = safeClassForName(stype);
        if (entityTypes.containsKey(stype)
                || mappedSupertypes.containsKey(stype)) {
            while (true) {
                ClassModel sdecl;
                if (entityTypes.containsKey(stype)) {
                    sdecl = entityTypes.get(stype);
                } else if (mappedSupertypes.containsKey(stype)) {
                    sdecl = mappedSupertypes.get(stype);
                } else {
                    return;
                }
                typeDecl.include(sdecl);
                stype = sdecl.getSupertypeName();
            }

        } else if (superClass != null && !superClass.equals(Object.class)) {
            // TODO : recursively up ?
            ClassModel type = ClassModelFactory.createType(superClass);
            // include fields of supertype
            typeDecl.include(type);
        }
    }

    private Class<?> safeClassForName(String stype) {
        try {
            return stype != null ? Class.forName(stype) : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    protected DefaultEntityVisitor createEntityVisitor() {
        return new DefaultEntityVisitor();
    }

    protected DefaultDTOVisitor createDTOVisitor() {
        return new DefaultDTOVisitor();
    }

    private void createDomainClasses() {
        DefaultEntityVisitor superclassVisitor = createEntityVisitor();

        // mapped superclass
        AnnotationTypeDeclaration a;
        Map<String, ClassModel> mappedSupertypes;
        if (superClassAnnotation != null) {
            a = (AnnotationTypeDeclaration) env
                    .getTypeDeclaration(superClassAnnotation);
            for (Declaration typeDecl : env.getDeclarationsAnnotatedWith(a)) {
                typeDecl
                        .accept(getDeclarationScanner(superclassVisitor, NO_OP));
            }
            mappedSupertypes = superclassVisitor.types;
        } else {
            mappedSupertypes = new HashMap<String, ClassModel>();
        }

        // domain types
        DefaultEntityVisitor entityVisitor = createEntityVisitor();
        a = (AnnotationTypeDeclaration) env
                .getTypeDeclaration(domainAnnotation);
        for (Declaration typeDecl : env.getDeclarationsAnnotatedWith(a)) {
            typeDecl.accept(getDeclarationScanner(entityVisitor, NO_OP));
        }
        Map<String, ClassModel> entityTypes = entityVisitor.types;

        for (ClassModel typeDecl : entityTypes.values()) {
            addSupertypeFields(typeDecl, entityTypes, mappedSupertypes);
        }

        if (entityTypes.isEmpty()) {
            env.getMessager().printNotice(
                    "No class generation for domain types");
        } else {
            serializeAsOuterClasses(entityTypes.values(), Serializers.DOMAIN);
        }

    }

    private void createDTOClasses() {
        AnnotationTypeDeclaration a = (AnnotationTypeDeclaration) env
                .getTypeDeclaration(dtoAnnotation);
        DefaultDTOVisitor dtoVisitor = createDTOVisitor();
        for (Declaration typeDecl : env.getDeclarationsAnnotatedWith(a)) {
            typeDecl.accept(getDeclarationScanner(dtoVisitor, NO_OP));
        }
        if (dtoVisitor.types.isEmpty()) {
            env.getMessager().printNotice("No class generation for DTO types");
        } else {
            serializeAsOuterClasses(dtoVisitor.types, Serializers.DTO);
        }

    }

    public void process() {
        if (domainAnnotation != null) {
            createDomainClasses();
        }
        if (dtoAnnotation != null) {
            createDTOClasses();
        }
    }

    protected void serializeAsOuterClasses(Collection<ClassModel> entityTypes, Serializer serializer) {
        // populate model
        Map<String, Object> model = new HashMap<String, Object>();
        model.put("pre", namePrefix);

        for (ClassModel type : entityTypes) {
            String packageName = type.getPackageName();
            model.put("package", packageName);
            model.put("type", type);
            model.put("classSimpleName", type.getSimpleName());

            // serialize it
            try {
                String path = packageName.replace('.', '/') + "/" + namePrefix + type.getSimpleName() + ".java";
                serializer.serialize(model, FileUtils.writerFor(new File(targetFolder, path)));
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

}
