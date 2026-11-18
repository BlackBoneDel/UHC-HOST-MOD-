package com.blackbone.uhccore.libs.sqlite;

/** Hikari's namespaced entry point. The upstream org.sqlite JNI implementation
 * must retain its native symbol names; Paper isolates it in this plugin classloader. */
public final class SQLiteDriver extends org.sqlite.JDBC { }
