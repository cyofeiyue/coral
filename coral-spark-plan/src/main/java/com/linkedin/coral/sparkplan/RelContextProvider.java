/**
 * Copyright 2019 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.sparkplan;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.linkedin.coral.hive.hive2rel.DaliOperatorTable;
import com.linkedin.coral.hive.hive2rel.HiveConvertletTable;
import com.linkedin.coral.hive.hive2rel.HiveDbSchema;
import com.linkedin.coral.hive.hive2rel.HiveSchema;
import com.linkedin.coral.hive.hive2rel.HiveTypeSystem;
import com.linkedin.coral.hive.hive2rel.functions.HiveFunctionRegistry;
import com.linkedin.coral.hive.hive2rel.functions.StaticHiveFunctionRegistry;
import com.linkedin.coral.hive.hive2rel.parsetree.ParseTreeBuilder;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nonnull;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.Driver;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;


/**
 * TODO: Modify this file as it is borrowed from coral-hive (in coral hive, this class is not public)
 *
 * Calcite needs different objects that are not trivial to create. This class
 * simplifies creation of objects, required by Calcite, easy. These objects
 * are created only once and shared across each call to corresponding getter.
 */
// TODO: Replace this with Google injection framework
class RelContextProvider {

  private final FrameworkConfig config;
  private final HiveSchema schema;
  private RelBuilder relBuilder;
  private CalciteCatalogReader catalogReader;
  private RelOptCluster cluster;
  private final HiveConvertletTable convertletTable = new HiveConvertletTable();
  private Driver driver;

  // maintain a mutable copy of Hive function registry in order to save some UDF information
  // resolved at run time.  For example, dependencies information.
  private HiveFunctionRegistry registry;

  /**
   * Instantiates a new Rel context provider.
   *
   * @param schema {@link HiveSchema} to use for conversion to relational algebra
   */
  RelContextProvider(@Nonnull HiveSchema schema) {
    Preconditions.checkNotNull(schema);
    this.schema = schema;
    SchemaPlus schemaPlus = Frameworks.createRootSchema(false);
    schemaPlus.add(HiveSchema.ROOT_SCHEMA, schema);
    this.registry = new StaticHiveFunctionRegistry();
    // this is to ensure that jdbc:calcite driver is correctly registered
    // before initializing framework (which needs it)
    // We don't want each engine to register the driver. It may not also load correctly
    // if the service uses its own service loader (see Presto)
    driver = new Driver();
    config = Frameworks.newConfigBuilder()
        .convertletTable(convertletTable)
        .defaultSchema(schemaPlus)
        .typeSystem(new HiveTypeSystem())
        .traitDefs((List<RelTraitDef>) null)
        .operatorTable(ChainedSqlOperatorTable.of(SqlStdOperatorTable.instance(), new DaliOperatorTable(schema, this.registry)))
        .programs(Programs.ofRules(Programs.RULE_SET))
        .build();
  }

  /**
   * Gets the local copy of HiveFunctionRegistry for current query.
   *
   * @return HiveFunctionRegistry map
   */
  public HiveFunctionRegistry getHiveFunctionRegistry() {
    return this.registry;
  }

  /**
   * Gets {@link FrameworkConfig} for creation of various objects
   * from Calcite object model
   *
   * @return FrameworkConfig object
   */
  FrameworkConfig getConfig() {
    return config;
  }

  ParseTreeBuilder.Config getParseTreeBuilderConfig() {
    return new ParseTreeBuilder.Config()
        .setCatalogName(HiveSchema.ROOT_SCHEMA)
        .setDefaultDB(HiveDbSchema.DEFAULT_DB);
  }

  HiveSchema getHiveSchema() {
    return schema;
  }

  /**
   * Gets {@link RelBuilder} object for generating relational algebra.
   *
   * @return the rel builder
   */
  RelBuilder getRelBuilder() {
    if (relBuilder == null) {
      // Turn off Rel simplification. Rel simplification can statically interpret boolean conditions in
      // OR, AND, CASE clauses and simplify those. This has two problems:
      // 1. Our type system is not perfect replication of Hive so this can be incorrect
      // 2. Converted expression is harder to validate for correctness(because it appears different from input)
      Hook.REL_BUILDER_SIMPLIFY.add(Hook.propertyJ(false));
      relBuilder = RelBuilder.create(config);
    }
    return relBuilder;
  }

  /**
   * Gets calcite catalog reader.
   *
   * @return the calcite catalog reader
   */
  CalciteCatalogReader getCalciteCatalogReader() {
    CalciteConnectionConfig connectionConfig;
    if (config.getContext() != null) {
      connectionConfig = config.getContext().unwrap(CalciteConnectionConfig.class);
    } else {
      Properties properties = new Properties();
      properties.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(),
          String.valueOf(false));
      connectionConfig = new CalciteConnectionConfigImpl(properties);
    }
    if (catalogReader == null) {
      catalogReader =
          new CalciteCatalogReader(config.getDefaultSchema().unwrap(CalciteSchema.class),
              ImmutableList.of(HiveSchema.ROOT_SCHEMA, HiveSchema.DEFAULT_DB),
              getRelBuilder().getTypeFactory(),
              connectionConfig);
    }
    return catalogReader;
  }

  /**
   * Gets rel opt cluster.
   *
   * @return the rel opt cluster
   */
  RelOptCluster getRelOptCluster() {
    if (cluster == null) {
      cluster = RelOptCluster.create(new VolcanoPlanner(), getRelBuilder().getRexBuilder());
    }
    return cluster;
  }
}