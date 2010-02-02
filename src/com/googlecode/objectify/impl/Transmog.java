package com.googlecode.objectify.impl;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import javax.persistence.Embedded;
import javax.persistence.Id;

import com.google.appengine.api.datastore.Entity;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.annotation.OldName;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.annotation.Unindexed;

/**
 * <p>Class which knows how to load data from Entity to POJO and save data from POJO to Entity.</p>
 * <p>Note that this class completely ignores @Id and @Parent fields.</p>
 * <p>A useful thing to remember when trying to understand this class is that in an entity object
 * graph, arrays and collections of basic types are considered leaf nodes.  On the other hand,
 * arrays and collections of @Embedded actually fan out the graph.</p>
 * 
 * @author Jeff Schnitzer <jeff@infohazard.org>
 */
public class Transmog<T>
{
	/** Needed to convert Key types */
	ObjectifyFactory factory;
	
	/** Maps full "blah.blah.blah" property name to a particular Loader implementation */
	Map<String, Loader<T>> loaders;
	
	/**
	 * Object which visits various levels of the object graph and builds the loaders & savers.
	 * Tracks the current 
	 */
	class Visitor
	{
		String prefix = "";
		boolean embedded;
		boolean forceUnindexed;
		Navigator<T> navigator;
		
		/** Constructs a visitor for a top-level entity */
		public Visitor()
		{
			this.navigator = new RootNavigator<T>();
		}
		
		/**
		 * Constructs a visitor for an embedded object.
		 * @param navigator figures out how to build the path to the object upon which
		 *  we want to set properties when we find fields. 
		 */
		public Visitor(Navigator<T> nav, String prefix, boolean forceUnindexed)
		{
			this.navigator = nav;
			this.prefix = prefix;
			this.embedded = true;
			this.forceUnindexed = forceUnindexed;
		}
		
		/** The money shot */
		public void visitClass(Class<?> clazz)
		{
			if ((clazz == null) || (clazz == Object.class))
				return;

			this.visitClass(clazz.getSuperclass());

			for (Field field: clazz.getDeclaredFields())
			{
				this.visitField(field);
			}

			// Now look for methods with one param that are annotated with @OldName
//			for (Method method: clazz.getDeclaredMethods())
//			{
//				OldName oldName = method.getAnnotation(OldName.class);
//				if (oldName != null)
//				{
//					if (method.getParameterTypes().length != 1)
//						throw new IllegalStateException("@OldName methods must have a single parameter. Can't use " + method);
//
//					method.setAccessible(true);
//
//					this.addMethod(method, oldName.value());
//				}
//			}
		}
		
		/**
		 * We have a non-transient field field
		 */
		void visitField(Field field)
		{
			if (!TypeUtils.isSaveable(field)
					|| field.isAnnotationPresent(Id.class)
					|| field.isAnnotationPresent(Parent.class))
				return;

			field.setAccessible(true);
			
			boolean unindexed = this.forceUnindexed || field.isAnnotationPresent(Unindexed.class);
			
			if (field.isAnnotationPresent(Embedded.class))
			{
				if (field.getType().isArray())
				{
					TypeUtils.checkForNoArgConstructor(field.getType().getComponentType());
					
					EmbeddedArrayNavigator<T> nav = new EmbeddedArrayNavigator<T>(this.navigator, field);
					Visitor visitor = new Visitor(nav, this.prefix + field.getName(), unindexed);
					visitor.visitClass(field.getType());
					
					throw new UnsupportedOperationException("@Embedded arrays not supported yet");
				}
				else if (Collection.class.isAssignableFrom(field.getType()))
				{
					throw new UnsupportedOperationException("@Embedded arrays not supported yet");
				}
				else	// basic class
				{
					TypeUtils.checkForNoArgConstructor(field.getType());
					
					EmbeddedClassNavigator<T> nav = new EmbeddedClassNavigator<T>(this.navigator, field);
					Visitor visitor = new Visitor(nav, this.prefix + field.getName(), unindexed);
					visitor.visitClass(field.getType());
				}
			}
			else	// not embedded, so we're at a leaf object (including arrays of basic types)
			{
				Loader<T> loader;
				
				if (field.getType().isArray())
				{
					loader = new ArrayLoader<T>(factory, this.navigator, field);
				}
				else if (Collection.class.isAssignableFrom(field.getType()))
				{
					loader = new CollectionLoader<T>(factory, this.navigator, field);
				}
				else
				{
					loader = new BasicLoader<T>(factory, this.navigator, field);
				}
				
				this.addLoader(field.getName(), loader);
				
				OldName oldName = field.getAnnotation(OldName.class);
				if (oldName != null)
					this.addLoader(oldName.value(), loader);
			}
		}
		
		/**
		 * Adds a final loader to the loaders collection.
		 * @param name is the short, immediate name of the property
		 */
		void addLoader(String name, Loader<T> loader)
		{
			String wholeName = this.prefix + "." + name;
			if (loaders.containsKey(wholeName))
				throw new IllegalStateException("Attempting to create multiple associations for " + wholeName);
			
			loaders.put(wholeName, loader);
		}
	}
	
	/**
	 * Creats a transmog for the specified class, introspecting it and discovering
	 * how to load/save its properties.
	 */
	public Transmog(ObjectifyFactory fact, Class<T> clazz)
	{
		this.factory = fact;
		new Visitor().visitClass(clazz);
	}
	
	/**
	 * Loads the property data in an Entity into a POJO.  Does not affect id/parent
	 * (ie key) fields; those are assumed to already have been set.
	 * 
	 * @param entity is a raw datastore entity
	 * @param pojo is your typed entity
	 */
	public void load(Entity entity, T pojo)
	{
		for (Map.Entry<String, Object> property: entity.getProperties().entrySet())
		{
			Loader<T> loader = this.loaders.get(property.getKey());
			if (loader != null)
			{
				loader.load(pojo, property.getValue());
			}
		}
	}
	
	/**
	 * Saves the fields of a POJO ito the properties of an Entity.  Does not affect id/parent
	 * (ie key) fields; those are assumed to already have been set.
	 * 
	 * @param pojo is your typed entity
	 * @param entity is a raw datastore entity
	 */
	public void save(T pojo, Entity entity)
	{
		
	}
}
