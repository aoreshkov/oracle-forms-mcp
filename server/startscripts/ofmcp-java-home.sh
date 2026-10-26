# OFMCP_JAVA_HOME names the Java installation to run on, ahead of JAVA_HOME. A client that can only
# set variables for us (the MCPB bundle's settings) can then choose a Java without clearing the
# JAVA_HOME the user already has: mapping an empty setting onto JAVA_HOME would hide it. Empty, or a
# ${user_config.*} placeholder the client left unsubstituted, means unset. Spliced in by the
# startScripts task in server/build.gradle.kts.
case $OFMCP_JAVA_HOME in
    '' | *'${user_config.'*) ;;
    *)
        if [ -x "$OFMCP_JAVA_HOME/bin/java" ] ; then
            JAVA_HOME=$OFMCP_JAVA_HOME
        elif [ -x "$OFMCP_JAVA_HOME/Contents/Home/bin/java" ] ; then
            # A macOS .jdk bundle, as a folder picker shows it.
            JAVA_HOME=$OFMCP_JAVA_HOME/Contents/Home
        elif [ -x "$OFMCP_JAVA_HOME/java" ] ; then
            # The installation's bin folder.
            JAVA_HOME=$OFMCP_JAVA_HOME/..
        else
            die "ERROR: OFMCP_JAVA_HOME is set to $OFMCP_JAVA_HOME, which has no bin/java.

Set it (in the desktop bundle: the Java installation setting) to the folder of a
Java 25+ installation, the one that contains bin/java, or clear it to use JAVA_HOME
or the java on PATH."
        fi
        ;;
esac

