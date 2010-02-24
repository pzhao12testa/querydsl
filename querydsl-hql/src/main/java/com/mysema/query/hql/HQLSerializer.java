/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.hql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.mysema.query.JoinExpression;
import com.mysema.query.JoinType;
import com.mysema.query.QueryMetadata;
import com.mysema.query.serialization.SerializerBase;
import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.expr.Constant;
import com.mysema.query.types.expr.EBoolean;
import com.mysema.query.types.expr.EStringConst;
import com.mysema.query.types.expr.Expr;
import com.mysema.query.types.operation.OSimple;
import com.mysema.query.types.operation.Operation;
import com.mysema.query.types.operation.Operator;
import com.mysema.query.types.operation.Ops;
import com.mysema.query.types.path.PCollection;
import com.mysema.query.types.path.PEntity;
import com.mysema.query.types.path.PList;
import com.mysema.query.types.path.PSet;
import com.mysema.query.types.path.Path;
import com.mysema.query.types.path.PathType;
import com.mysema.query.types.query.SubQuery;

/**
 * HQLSerializer serializes querydsl expressions into HQL syntax.
 * 
 * @author tiwe
 * @version $Id$
 */
public class HQLSerializer extends SerializerBase<HQLSerializer> {

    private static final Map<JoinType, String> joinTypes = new HashMap<JoinType, String>();
    
    static{
        joinTypes.put(JoinType.DEFAULT, ", ");
        joinTypes.put(JoinType.FULLJOIN, "\n  full join ");
        joinTypes.put(JoinType.INNERJOIN, "\n  inner join ");
        joinTypes.put(JoinType.JOIN, "\n  join ");
        joinTypes.put(JoinType.LEFTJOIN, "\n  left join ");
    }
    
    private boolean wrapElements = false;

    public HQLSerializer(HQLTemplates patterns) {
        super(patterns);
    }

    public void serializeForDelete(QueryMetadata md) {
        append("delete ");
        handleJoinTarget(md.getJoins().get(0));        
        if (md.getWhere() != null) {
            append("\nwhere ").handle(md.getWhere());
        }
    }

    public void serializeForUpdate(QueryMetadata md) {
        append("update ");
        handleJoinTarget(md.getJoins().get(0));
        append("\nset ");
        handle(", ", md.getProjection());
        if (md.getWhere() != null) {
            append("\nwhere ").handle(md.getWhere());
        }
    }
    
    public void serialize(QueryMetadata metadata, boolean forCountRow, @Nullable String projection) {
        List<? extends Expr<?>> select = metadata.getProjection();
        List<JoinExpression> joins = metadata.getJoins();
        EBoolean where = metadata.getWhere();
        List<? extends Expr<?>> groupBy = metadata.getGroupBy();
        EBoolean having = metadata.getHaving();
        List<OrderSpecifier<?>> orderBy = metadata.getOrderBy();

        // select
        if (projection != null){
            append("select ").append(projection).append("\n");
        }else if (forCountRow) {
            append("select count(*)\n");
        } else if (!select.isEmpty()) {
            if (!metadata.isDistinct()) {
                append("select ");
            } else {
                append("select distinct ");
            }
            handle(", ", select).append("\n");
        }
        
        // from
        append("from ");
        serializeSources(forCountRow, joins);

        // where
        if (where != null) {
            append("\nwhere ").handle(where);
        }
        
        // group by
        if (!groupBy.isEmpty()) {
            append("\ngroup by ").handle(", ", groupBy);
        }
        
        // having
        if (having != null) {
            if (groupBy.isEmpty()) {
                throw new IllegalArgumentException(
                        "having, but not groupBy was given");
            }
            append("\nhaving ").handle(having);
        }
        
        // order by
        if (!orderBy.isEmpty() && !forCountRow) {
            append("\norder by ");
            boolean first = true;
            for (OrderSpecifier<?> os : orderBy) {
                if (!first){
                    append(", ");
                }                    
                handle(os.getTarget());
                append(" " + os.getOrder().toString().toLowerCase());
                first = false;
            }
        }
    }

    private void serializeSources(boolean forCountRow, List<JoinExpression> joins) {
        for (int i = 0; i < joins.size(); i++) {
            JoinExpression je = joins.get(i);
            if (i > 0) {
                append(joinTypes.get(je.getType()));
            }            
            if (je.hasFlag(HQLFlags.FETCH) && !forCountRow){
                append("fetch ");
            }            
            handleJoinTarget(je);
            if (je.hasFlag(HQLFlags.FETCH_ALL) && !forCountRow){
                append(" fetch all properties");
            }

            if (je.getCondition() != null) {
                append(" with ").handle(je.getCondition());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleJoinTarget(JoinExpression je) {
        // type specifier
        if (je.getTarget() instanceof PEntity) {
            PEntity<?> pe = (PEntity<?>) je.getTarget();
            if (pe.getMetadata().getParent() == null) {
                String pn = pe.getType().getPackage().getName();
                String typeName = pe.getType().getName().substring(pn.length() + 1);
                append(typeName).append(" ");
            }
        }
        handle(je.getTarget());
    }

    @SuppressWarnings("unchecked")
    @Override
    public void visit(Constant<?> expr) {
        boolean wrap = expr.getConstant().getClass().isArray() || expr.getConstant() instanceof Collection;
        if (wrap) {
            append("(");
        }
        append(":");
        if (!getConstantToLabel().containsKey(expr.getConstant())) {
            String constLabel = getConstantPrefix() + (getConstantToLabel().size()+1);
            getConstantToLabel().put(expr.getConstant(), constLabel);
            append(constLabel);
        } else {
            append(getConstantToLabel().get(expr.getConstant()));
        }
        if (wrap) {
            append(")");
        }
    }

    @Override
    public void visit(PCollection<?> expr) {
        visitCollection(expr);
    }
    
    @Override
    public void visit(PList<?,?> expr) {
        visitCollection(expr);
    }
    
    @Override
    public void visit(PSet<?> expr) {
        visitCollection(expr);
    }

    private void visitCollection(Path<?> expr){
        // only wrap a PathCollection, if it the pathType is PROPERTY
        boolean wrap = wrapElements && expr.getMetadata().getPathType().equals(PathType.PROPERTY);
        if (wrap) {
            append("elements(");
        }
        visit((Path<?>) expr);
        if (wrap) {
            append(")");
        }
    }
    
    @Override
    public void visit(SubQuery query) {
        append("(");       
        serialize(query.getMetadata(), false, null);
        append(")");
    }

    private void visitCast(Expr<?> source, Class<?> targetType) {
        append("cast(").handle(source);
        append(" as ");
        append(targetType.getSimpleName().toLowerCase()).append(")");
    }

    @SuppressWarnings("unchecked")
    protected void visitOperation(Class<?> type, Operator<?> operator, List<Expr<?>> args) {
        boolean old = wrapElements;
        wrapElements = HQLTemplates.wrapCollectionsForOp.contains(operator);
        // 
        if (operator.equals(Ops.INSTANCE_OF)) {
            args = new ArrayList<Expr<?>>(args);
            args.set(1, EStringConst.create(((Class<?>) ((Constant<?>) args.get(1)).getConstant()).getName()));
            super.visitOperation(type, operator, args);
            
        } else if (operator.equals(Ops.NUMCAST)) {
            visitCast(args.get(0), (Class<?>) ((Constant<?>) args.get(1)).getConstant());
            
        } else if (operator.equals(Ops.EXISTS) && args.get(0) instanceof SubQuery){
            SubQuery subQuery = (SubQuery) args.get(0);            
            append("exists (");
            serialize(subQuery.getMetadata(), false, "1");
            append(")");
            
        } else if (operator.equals(Ops.MATCHES)){
            args = new ArrayList<Expr<?>>(args);
            if (args.get(1) instanceof Constant){
                args.set(1, regexToLike(args.get(1).toString()));
            }else if (args.get(1) instanceof Operation){
                args.set(1, regexToLike((Operation)args.get(1)));
            }
            super.visitOperation(type, operator, args);
            
        } else {
            super.visitOperation(type, operator, args);
        }
        //
        wrapElements = old;
    }

    // TODO : generalize this!
    @SuppressWarnings("unchecked")
    private Expr<?> regexToLike(Operation<?,?> operation) {
        List<Expr<?>> args = new ArrayList<Expr<?>>();
        for (Expr<?> arg : operation.getArgs()){
            if (!arg.getType().equals(String.class)){
                args.add(arg);
            }else if (arg instanceof Constant){
                args.add(regexToLike(arg.toString()));
            }else if (arg instanceof Operation){
                args.add(regexToLike((Operation)arg));
            }else{
                args.add(arg);
            }
        }
        return OSimple.create(
                operation.getType(),
                operation.getOperator(), 
                args.<Expr<?>>toArray(new Expr[args.size()]));
    }

    private Expr<?> regexToLike(String str){
        return EStringConst.create(str.replace(".*", "%").replace(".", "_"));
    }

}
