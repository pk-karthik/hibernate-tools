package org.hibernate.cfg.reveng;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.collections.MultiMap;
import org.dom4j.Document;
import org.hibernate.MappingException;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.xml.ErrorLogger;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Table;
import org.hibernate.tool.util.TableNameQualifier;
import org.hibernate.tool.xml.XMLHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

public class OverrideRepository  {

	final private static Logger log = LoggerFactory.getLogger( OverrideRepository.class );

	final private Map<TypeMappingKey, List<SQLTypeMapping>> typeMappings; // from sqltypes to list of SQLTypeMapping

	final private List<TableFilter> tableFilters;

	final private List tables;
	final private Map foreignKeys; // key: TableIdentifier element: List of foreignkeys that references the Table

	final private Map typeForColumn;

	final private Map propertyNameForColumn;

	final private Map identifierStrategyForTable;

	final private Map identifierPropertiesForTable;

	final private Map primaryKeyColumnsForTable;

	final private Set excludedColumns;

	final private Map tableToClassName;

	final private List<SchemaSelection> schemaSelections;

	final private Map propertyNameForPrimaryKey;

	final private Map compositeIdNameForTable;

	final private Map foreignKeyToOneName;

	final private Map foreignKeyToInverseName;

	final private Map foreignKeyInverseExclude;

	final private Map foreignKeyToOneExclude;

	final private Map foreignKeyToEntityInfo;
	final private Map foreignKeyToInverseEntityInfo;

	final private Map tableMetaAttributes; // TI -> MultiMap of SimpleMetaAttributes

	final private Map columnMetaAttributes;

	//private String defaultCatalog;
	//private String defaultSchema;

	public OverrideRepository() {
		//this.defaultCatalog = null;
		//this.defaultSchema = null;
		typeMappings = new HashMap<TypeMappingKey, List<SQLTypeMapping>>();
		tableFilters = new ArrayList<TableFilter>();
		tables = new ArrayList();
		foreignKeys = new HashMap();
		typeForColumn = new HashMap();
		propertyNameForColumn = new HashMap();
		identifierStrategyForTable = new HashMap();
		identifierPropertiesForTable = new HashMap();
		primaryKeyColumnsForTable = new HashMap();
		propertyNameForPrimaryKey = new HashMap();
		tableToClassName = new HashMap();
		excludedColumns = new HashSet();
		schemaSelections = new ArrayList();
		compositeIdNameForTable = new HashMap();
		foreignKeyToOneName = new HashMap();
		foreignKeyToInverseName = new HashMap();
		foreignKeyInverseExclude = new HashMap();
		foreignKeyToOneExclude = new HashMap();
		tableMetaAttributes = new HashMap();
		columnMetaAttributes = new HashMap();
		foreignKeyToEntityInfo = new HashMap();
		foreignKeyToInverseEntityInfo = new HashMap();
	}

	public OverrideRepository addFile(File xmlFile) {
		log.info( "Override file: " + xmlFile.getPath() );
		try {
			addInputStream( new FileInputStream( xmlFile ) );
		}
		catch ( Exception e ) {
			log.error( "Could not configure overrides from file: " + xmlFile.getPath(), e );
			throw new MappingException( "Could not configure overrides from file: " + xmlFile.getPath(), e );
		}
		return this;

	}

	/**
	 * Read override from an application resource trying different classloaders.
	 * This method will try to load the resource first from the thread context
	 * classloader and then from the classloader that loaded Hibernate.
	 */
	public OverrideRepository addResource(String path) throws MappingException {
		log.info( "Mapping resource: " + path );
		InputStream rsrc = Thread.currentThread().getContextClassLoader().getResourceAsStream( path );
		if ( rsrc == null ) rsrc = OverrideRepository.class.getClassLoader().getResourceAsStream( path );
		if ( rsrc == null ) throw new MappingException( "Resource: " + path + " not found" );
		try {
			return addInputStream( rsrc );
		}
		catch ( MappingException me ) {
			throw new MappingException( "Error reading resource: " + path, me );
		}
	}


	public OverrideRepository addInputStream(InputStream xmlInputStream) throws MappingException {
		try {
			ErrorLogger errorLogger = new ErrorLogger( "XML InputStream" );
			org.dom4j.Document doc = XMLHelper.createSAXReader( errorLogger).read( new InputSource( xmlInputStream ) );
			if ( errorLogger.hasErrors() ) throw new MappingException( "invalid override definition", ( Throwable ) errorLogger.getErrors().get( 0 ) );
			add( doc );
			return this;
		}
		catch ( MappingException me ) {
			throw me;
		}
		catch ( Exception e ) {
			log.error( "Could not configure overrides from input stream", e );
			throw new MappingException( e );
		}
		finally {
			try {
				xmlInputStream.close();
			}
			catch ( IOException ioe ) {
				log.error( "could not close input stream", ioe );
			}
		}
	}

	private OverrideRepository add(Document doc) {
		OverrideBinder.bindRoot(this, doc);
		return this;
	}

	private String getPreferredHibernateType(int sqlType, int length, int precision, int scale, boolean nullable) {
		List<SQLTypeMapping> l = typeMappings.get(new TypeMappingKey(sqlType,length) );

		if(l == null) { // if no precise length match found, then try to find matching unknown length matches
			l = typeMappings.get(new TypeMappingKey(sqlType,SQLTypeMapping.UNKNOWN_LENGTH) );
		}
		return scanForMatch( sqlType, length, precision, scale, nullable, l );
	}

	private String scanForMatch(int sqlType, int length, int precision, int scale, boolean nullable, List l) {
		if(l!=null) {
			Iterator iterator = l.iterator();
			while (iterator.hasNext() ) {
				SQLTypeMapping element = (SQLTypeMapping) iterator.next();
				if(element.getJDBCType()!=sqlType) return null;
				if(element.match(sqlType, length, precision, scale, nullable) ) {
					return element.getHibernateType();
				}
			}
		}
		return null;
	}

	public OverrideRepository addTypeMapping(SQLTypeMapping sqltype) {
		TypeMappingKey key = new TypeMappingKey(sqltype);
		List<SQLTypeMapping> list = typeMappings.get(key);
		if(list==null) {
			list = new ArrayList<SQLTypeMapping>();
			typeMappings.put(key, list);
		}
		list.add(sqltype);
		return this;
	}

	static class TypeMappingKey {

		int type;
		int length;

		TypeMappingKey(SQLTypeMapping mpa) {
			type = mpa.getJDBCType();
			length = mpa.getLength();
		}

		public TypeMappingKey(int sqlType, int length) {
			this.type = sqlType;
			this.length = length;
		}

		public boolean equals(Object obj) {
			if(obj==null) return false;
			if(!(obj instanceof TypeMappingKey)) return false;
			TypeMappingKey other = (TypeMappingKey) obj;


			return type==other.type && length==other.length;
		}

		public int hashCode() {
			return (type + length) % 17;
		}

		public String toString() {
			return this.getClass() + "(type:" + type + ", length:" + length + ")";
		}
	}

	protected String getPackageName(TableIdentifier identifier) {
		Iterator<TableFilter> iterator = tableFilters.iterator();
		while(iterator.hasNext() ) {
			TableFilter tf = iterator.next();
			String value = tf.getPackage(identifier);
			if(value!=null) {
				return value;
			}
		}
		return null;
	}

	protected boolean excludeTable(TableIdentifier identifier) {
		Iterator<TableFilter> iterator = tableFilters.iterator();
		boolean hasInclude = false;

		while(iterator.hasNext() ) {
			TableFilter tf = iterator.next();
			Boolean value = tf.exclude(identifier);
			if(value!=null) {
				return value.booleanValue();
			}
			if(!tf.getExclude().booleanValue()) {
				hasInclude = true;
			}
		}

		// can probably be simplified - but like this to be very explicit ;)
		if(hasInclude) {
			return true; // exclude all by default when at least one include specified
		} else {
			return false; // if nothing specified or just excludes we include everything
		}
	}

	public void addTableFilter(TableFilter filter) {
		tableFilters.add(filter);
	}

	public ReverseEngineeringStrategy getReverseEngineeringStrategy(ReverseEngineeringStrategy delegate) {
		return new DelegatingReverseEngineeringStrategy(delegate) {

			public boolean excludeTable(TableIdentifier ti) {
				return OverrideRepository.this.excludeTable(ti);
			}

			public Map tableToMetaAttributes(TableIdentifier tableIdentifier) {
				return OverrideRepository.this.tableToMetaAttributes(tableIdentifier);
			}

			public Map columnToMetaAttributes(TableIdentifier tableIdentifier, String column) {
				return OverrideRepository.this.columnToMetaAttributes(tableIdentifier, column);
			}

			public boolean excludeColumn(TableIdentifier identifier, String columnName) {
				return excludedColumns.contains(new TableColumnKey(identifier, columnName));
			}

			public String tableToCompositeIdName(TableIdentifier identifier) {
				String result = (String) compositeIdNameForTable.get(identifier);
				if(result==null) {
					return super.tableToCompositeIdName(identifier);
				} else {
					return result;
				}
			}
			public List<SchemaSelection> getSchemaSelections() {
				if(schemaSelections.isEmpty()) {
					return super.getSchemaSelections();
				} else {
					return schemaSelections;
				}
			}

			public String columnToHibernateTypeName(TableIdentifier table, String columnName, int sqlType, int length, int precision, int scale, boolean nullable, boolean generatedIdentifier) {
				String result = null;
				String location = "";
				String info = " t:" + JDBCToHibernateTypeHelper.getJDBCTypeName( sqlType ) + " l:" + length + " p:" + precision + " s:" + scale + " n:" + nullable + " id:" + generatedIdentifier;
				if(table!=null) {
					location = TableNameQualifier.qualify(table.getCatalog(), table.getSchema(), table.getName() ) + "." + columnName;
				} else {

					location += " Column: " + columnName + info;
				}
				if(table!=null && columnName!=null) {
					result = (String) typeForColumn.get(new TableColumnKey(table, columnName));
					if(result!=null) {
						log.debug("explicit column mapping found for [" + location + "] to [" + result + "]");
						return result;
					}
				}

				result = OverrideRepository.this.getPreferredHibernateType(sqlType, length, precision, scale, nullable);
				if(result==null) {
					return super.columnToHibernateTypeName(table, columnName, sqlType, length, precision, scale, nullable, generatedIdentifier);
				}
				else {
					log.debug("<type-mapping> found for [" + location + info + "] to [" + result + "]");
					return result;
				}
			}

			public String tableToClassName(TableIdentifier tableIdentifier) {
				String className = (String) tableToClassName.get(tableIdentifier);

				if(className!=null) {
					 if(className.indexOf( "." )>=0) {
						 return className;
					 } else {
						 String packageName = getPackageName(tableIdentifier);
						 if(packageName==null) {
							 return className;
						 } else {
							 return StringHelper.qualify(packageName, className);
						 }
					 }
				}

				String packageName = getPackageName(tableIdentifier);
				if(packageName==null) {
					return super.tableToClassName(tableIdentifier);
				}
				else {
					String string = super.tableToClassName(tableIdentifier);
					if(string==null) return null;
					return StringHelper.qualify(packageName, StringHelper.unqualify(string));
				}
			}

			public List<ForeignKey> getForeignKeys(TableIdentifier referencedTable) {
				List<ForeignKey> list = (List<ForeignKey>) foreignKeys.get(referencedTable);
				if(list==null) {
					return super.getForeignKeys(referencedTable);
				} else {
					return list;
				}
			}

			public String columnToPropertyName(TableIdentifier table, String column) {
				String result = (String) propertyNameForColumn.get(new TableColumnKey(table, column));
				if(result==null) {
					return super.columnToPropertyName(table, column);
				} else {
					return result;
				}
			}

			public String tableToIdentifierPropertyName(TableIdentifier tableIdentifier) {
				String result = (String) propertyNameForPrimaryKey.get(tableIdentifier);
				if(result==null) {
					return super.tableToIdentifierPropertyName(tableIdentifier);
				} else {
					return result;
				}
			}

			public String getTableIdentifierStrategyName(TableIdentifier tableIdentifier) {
				String result = (String) identifierStrategyForTable.get(tableIdentifier);
				if(result==null) {
					return super.getTableIdentifierStrategyName( tableIdentifier );
				} else {
					log.debug("tableIdentifierStrategy for " + tableIdentifier + " -> '" + result + "'");
					return result;
				}
			}

			public Properties getTableIdentifierProperties(TableIdentifier tableIdentifier) {
				Properties result = (Properties) identifierPropertiesForTable.get(tableIdentifier);
				if(result==null) {
					return super.getTableIdentifierProperties( tableIdentifier );
				} else {
					return result;
				}
			}

			public List getPrimaryKeyColumnNames(TableIdentifier tableIdentifier) {
				List result = (List) primaryKeyColumnsForTable.get(tableIdentifier);
				if(result==null) {
					return super.getPrimaryKeyColumnNames(tableIdentifier);
				} else {
					return result;
				}
			}

			public String foreignKeyToEntityName(String keyname, TableIdentifier fromTable, List fromColumnNames, TableIdentifier referencedTable, List referencedColumnNames, boolean uniqueReference) {
				String property = (String) foreignKeyToOneName.get(keyname);
				if(property==null) {
					return super.foreignKeyToEntityName(keyname, fromTable, fromColumnNames, referencedTable, referencedColumnNames, uniqueReference);
				} else {
					return property;
				}
			}


			public String foreignKeyToInverseEntityName(String keyname,
					TableIdentifier fromTable, List fromColumnNames,
					TableIdentifier referencedTable,
					List referencedColumnNames, boolean uniqueReference) {

				String property = (String) foreignKeyToInverseName.get(keyname);
				if(property==null) {
					return super.foreignKeyToInverseEntityName(keyname, fromTable, fromColumnNames, referencedTable, referencedColumnNames, uniqueReference);
				} else {
					return property;
				}
			}

			public String foreignKeyToCollectionName(String keyname, TableIdentifier fromTable, List fromColumns, TableIdentifier referencedTable, List referencedColumns, boolean uniqueReference) {
				String property = (String) foreignKeyToInverseName.get(keyname);
				if(property==null) {
					return super.foreignKeyToCollectionName(keyname, fromTable, fromColumns, referencedTable, referencedColumns, uniqueReference);
				} else {
					return property;
				}
			}

			public boolean excludeForeignKeyAsCollection(String keyname, TableIdentifier fromTable, List fromColumns, TableIdentifier referencedTable, List referencedColumns) {
				Boolean bool = (Boolean) foreignKeyInverseExclude.get(keyname);
				if(bool!=null) {
					return bool.booleanValue();
				} else {
					return super.excludeForeignKeyAsCollection( keyname, fromTable, fromColumns,
							referencedTable, referencedColumns );
				}
			}

			public boolean excludeForeignKeyAsManytoOne(String keyname, TableIdentifier fromTable, List fromColumns, TableIdentifier referencedTable, List referencedColumns) {
				Boolean bool = (Boolean) foreignKeyToOneExclude.get(keyname);
				if(bool!=null) {
					return bool.booleanValue();
				} else {
					return super.excludeForeignKeyAsManytoOne( keyname, fromTable, fromColumns,
							referencedTable, referencedColumns );
				}
			}


			public AssociationInfo foreignKeyToInverseAssociationInfo(ForeignKey foreignKey) {
				AssociationInfo fkei = (AssociationInfo) foreignKeyToInverseEntityInfo.get(foreignKey.getName());
				if(fkei!=null) {
					return fkei;
				} else {
					return super.foreignKeyToInverseAssociationInfo(foreignKey);
				}
			}

			public AssociationInfo foreignKeyToAssociationInfo(ForeignKey foreignKey) {
				AssociationInfo fkei = (AssociationInfo) foreignKeyToEntityInfo.get(foreignKey.getName());
				if(fkei!=null) {
					return fkei;
				} else {
					return super.foreignKeyToAssociationInfo(foreignKey);
				}
			}
		};
	}

	protected Map columnToMetaAttributes(TableIdentifier tableIdentifier, String column) {
		Map specific = (Map) columnMetaAttributes.get( new TableColumnKey(tableIdentifier, column) );
		if(specific!=null && !specific.isEmpty()) {
			return toMetaAttributes(specific);
		}

		return null;
	}

	// TODO: optimize
	protected Map tableToMetaAttributes(TableIdentifier identifier) {
		Map specific = (Map) tableMetaAttributes.get( identifier );
		if(specific!=null && !specific.isEmpty()) {
			return toMetaAttributes(specific);
		}
		Map general = findGeneralAttributes( identifier );
		if(general!=null && !general.isEmpty()) {
			return toMetaAttributes(general);
		}

		return null;

		/* inheritance not defined yet
		 if(specific==null) { specific = Collections.EMPTY_MAP; }
		if(general==null) { general = Collections.EMPTY_MAP; }

		MultiMap map = MetaAttributeBinder.mergeMetaMaps( specific, general );
		*/
		/*
		if(map!=null && !map.isEmpty()) {
			return toMetaAttributes(null, map);
		} else {
			return null;
		}
		*/
	}

	private Map findGeneralAttributes(TableIdentifier identifier) {
		Iterator<TableFilter> iterator = tableFilters.iterator();
		while(iterator.hasNext() ) {
			TableFilter tf = iterator.next();
			Map value = tf.getMetaAttributes(identifier);
			if(value!=null) {
				return value;
			}
		}
		return null;
	}

	private Map toMetaAttributes(Map value) {
		Map result = new HashMap();

		Set set = value.entrySet();
		for (Iterator iter = set.iterator(); iter.hasNext();) {
			Map.Entry entry = (Map.Entry) iter.next();
			String name = (String) entry.getKey();
			List values = (List) entry.getValue();

			result.put(name, MetaAttributeBinder.toRealMetaAttribute(name, values));
		}

		return result;
	}

	public ReverseEngineeringStrategy getReverseEngineeringStrategy() {
		return getReverseEngineeringStrategy(null);
	}

	public void addTable(Table table, String wantedClassName) {
		Iterator fkIter = table.getForeignKeyIterator();
		while ( fkIter.hasNext() ) {
			ForeignKey fk = (ForeignKey) fkIter.next();
			TableIdentifier identifier = TableIdentifier.create(fk.getReferencedTable());
			List existing = (List) foreignKeys.get(identifier);
			if(existing==null) {
				existing = new ArrayList();
				foreignKeys.put(identifier, existing);
			}
			existing.add( fk );
		}

		tables.add(table);

		if(StringHelper.isNotEmpty(wantedClassName)) {
			tableToClassName.put(TableIdentifier.create(table), wantedClassName);
		}
	}

	static class TableColumnKey {
		private TableIdentifier query;
		private String name;
		
		TableColumnKey(TableIdentifier query, String name){
			this.query = query;
			this.name = name;
		}

		@Override
		public int hashCode() {
			final int prime = 29;
			int result = 1;
			result = prime * result + ((name == null) ? 0 : name.hashCode());
			result = prime * result + ((query == null) ? 0 : query.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			TableColumnKey other = (TableColumnKey) obj;
			if (name == null) {
				if (other.name != null)
					return false;
			} else if (!name.equals(other.name))
				return false;
			if (query == null) {
				if (other.query != null)
					return false;
			} else if (!query.equals(other.query))
				return false;
			return true;
		}
		
	}

	public void setTypeNameForColumn(TableIdentifier identifier, String columnName, String type) {
		if(StringHelper.isNotEmpty(type)) {
			typeForColumn.put(new TableColumnKey(identifier, columnName), type);
		}
	}

	public void setExcludedColumn(TableIdentifier tableIdentifier, String columnName) {
		excludedColumns.add(new TableColumnKey(tableIdentifier, columnName));
	}

	public void setPropertyNameForColumn(TableIdentifier identifier, String columnName, String property) {
		if(StringHelper.isNotEmpty(property)) {
			propertyNameForColumn.put(new TableColumnKey(identifier, columnName), property);
		}
	}

	public void addTableIdentifierStrategy(Table table, String identifierClass, Properties params) {
		if(identifierClass!=null) {
			final TableIdentifier tid = TableIdentifier.create(table);
			identifierStrategyForTable.put(tid, identifierClass);
			identifierPropertiesForTable.put(tid, params);
		}
	}

	public void addPrimaryKeyNamesForTable(Table table, List boundColumnNames, String propertyName, String compositeIdName) {
		TableIdentifier tableIdentifier = TableIdentifier.create(table);
		if(boundColumnNames!=null && !boundColumnNames.isEmpty()) {
			primaryKeyColumnsForTable.put(tableIdentifier, boundColumnNames);
		}
		if(StringHelper.isNotEmpty(propertyName)) {
			propertyNameForPrimaryKey.put(tableIdentifier, propertyName);
		}
		if(StringHelper.isNotEmpty(compositeIdName)) {
			compositeIdNameForTable.put(tableIdentifier, compositeIdName);
		}
	}

	/*public String getCatalog(String string) {
		return string==null?defaultCatalog:string;
	}*/

	/*public String getSchema(String string) {
		return string==null?defaultSchema:string;
	}*/

	public void addSchemaSelection(SchemaSelection schemaSelection) {
		schemaSelections.add(schemaSelection);
	}

	/**
	 * Both sides of the FK are important,
	 * the owning side can generate a toOne (ManyToOne or OneToOne), we call this side foreignKeyToOne
	 * the inverse side can generate a OneToMany OR a OneToOne (in case we have a pure bidirectional OneToOne, we call this side foreignKeyToInverse
	 */
	public void addForeignKeyInfo(String constraintName, String toOneProperty, Boolean excludeToOne, String inverseProperty, Boolean excludeInverse, AssociationInfo associationInfo, AssociationInfo inverseAssociationInfo) {
		if(StringHelper.isNotEmpty(toOneProperty)) {
			foreignKeyToOneName.put(constraintName, toOneProperty);
		}
		if(StringHelper.isNotEmpty(inverseProperty)) {
			foreignKeyToInverseName.put(constraintName, inverseProperty);
		}
		if(excludeInverse!=null) {
			foreignKeyInverseExclude.put(constraintName, excludeInverse);
		}
		if(excludeToOne!=null) {
			foreignKeyToOneExclude.put(constraintName, excludeToOne);
		}
		if(associationInfo!=null) {
			foreignKeyToEntityInfo.put(constraintName, associationInfo);
		}
		if(inverseAssociationInfo!=null) {
			foreignKeyToInverseEntityInfo.put(constraintName, inverseAssociationInfo);
		}

	}

	public void addMetaAttributeInfo(Table table, Map map) {
		if(map!=null && !map.isEmpty()) {
			tableMetaAttributes.put(TableIdentifier.create(table), map);
		}

	}

	public void addMetaAttributeInfo(TableIdentifier tableIdentifier, String name, MultiMap map) {
		if(map!=null && !map.isEmpty()) {
			columnMetaAttributes.put(new TableColumnKey( tableIdentifier, name ), map);
		}

	}




}
