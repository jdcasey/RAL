Resolved Application Launcher (RAL)
===================================

Usage:
------

java -jar remote-application-launcher-$VERSION-bin.jar [options] G:A:V -- [app-options]

G:A:V                  : Maven G:A:V for the artifact containing the main class to execute
-N (--no-exit)         : Skip calling System.exit
-P (--property)        : Define one or more system properties before launching
-c (--Main-Class) VAL  : Specify the main class to execute (for cases where the Main-Class Manifest
                          attribute isn't available)
-h (--help)            : Print this message and exit
-m (--Main-Method) VAL : Specify the main method to execute (default: 'main')

Description:
------------

This is a simple application launcher based on the Maven App Engine (MAE, currently in the Apache Maven sandbox), which resolves an application classpath given its main Maven coordinate (G:A:V), then launches it. It's currently fairly rough, but it serves as a fair example of how rapidly you can get things done with MAE.

