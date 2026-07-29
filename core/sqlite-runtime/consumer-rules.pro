# The vendored SQLite runtime registers native methods against classes in this
# namespace. Preserve both class and member names for every consuming app.
-keep class org.sqlite.** { *; }
