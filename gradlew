#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
_WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "ERROR: $*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MSYS* | MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
APP_HOME=$(dirname "$PRG")

# Ensure APP_HOME is absolute path
APP_HOME=$(cd "$APP_HOME" && pwd)

# For Cygwin, ensure paths are in UNIX format before anything is touched
if $cygwin ; then
    [ -n "$APP_HOME" ] &&
        APP_HOME=$(cygpath --unix "$APP_HOME")
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=$(cygpath --unix "$JAVA_HOME")
    [ -n "$CLASSPATH" ] &&
        CLASSPATH=$(cygpath --path --unix "$CLASSPATH")
fi

# For MSYS, ensure paths are in UNIX format before anything is touched
if $msys ; then
    [ -n "$APP_HOME" ] &&
        APP_HOME=$(pathconv -u "$APP_HOME")
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=$(pathconv -u "$JAVA_HOME")
    # TODO classpath?
fi

# Setup the JAVA_HOME prerequisite
if [ -z "$JAVA_HOME" ] ; then
  if $darwin ; then
    if [ -x '/usr/libexec/java_home' ] ; then
      JAVA_HOME=$(/usr/libexec/java_home)
    elif [ -d "/System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home" ]; then
      JAVA_HOME="/System/Library/Frameworks/JavaVM.framework/Versions/CurrentJDK/Home"
    fi
  else
    java_path=$(which java 2>/dev/null)
    if [ -n "$java_path" ] ; then
      java_path=$(readlink -f "$java_path" 2>/dev/null || readlink "$java_path" 2>/dev/null)
      if [ -n "$java_path" ] ; then
        JAVA_HOME=$(dirname "$(dirname "$java_path")" 2>/dev/null)
      fi
    fi
  fi
  if [ -z "$JAVA_HOME" ] ; then
    die "JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
  fi
fi

# Setup the Java Virtual Machine
if [ -z "$JAVACMD" ] ; then
  if [ -n "$JAVA_HOME"  ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
      # IBM's JDK on AIX uses strange locations for the executables
      JAVACMD="$JAVA_HOME/jre/sh/java"
    else
      JAVACMD="$JAVA_HOME/bin/java"
    fi
  else
    JAVACMD="java"
  fi
fi

if [ ! -x "$JAVACMD" ] ; then
  die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Set useful options for executing the JAR
# Automatically increase the maximum number of open files on relevant platforms.
if ! $cygwin && ! $msys && ! $nonstop ; then
    if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
        # Use system's max limit
        MAX_FD_LIMIT=$(ulimit -H -n)
        if [ $? -eq 0 ] ; then
            # Increase the soft limit up to the hard limit
            ulimit -n "$MAX_FD_LIMIT"
            if [ $? -ne 0 ] ; then
                warn "Could not set maximum file descriptor limit: $MAX_FD_LIMIT"
            fi
        else
            warn "Could not query maximum file descriptor limit"
        fi
    elif [ "$MAX_FD" -ne "-1" ] ; then
        # Use the specified limit
        ulimit -n "$MAX_FD"
        if [ $? -ne 0 ] ; then
            warn "Could not set file descriptor limit to $MAX_FD"
        fi
    fi
fi

# For Cygwin or MSYS, switch paths to Windows format before running java
if $cygwin || $msys ; then
    APP_HOME=$(pathconv -w "$APP_HOME")
    CLASSPATH=$(pathconv -w -p "$CLASSPATH")
    JAVACMD=$(pathconv -w "$JAVACMD")
    # Also for JAVA_HOME
    JAVA_HOME=$(pathconv -w "$JAVA_HOME")
fi


# Add the wrapper JAR to the CLASSPATH
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
CLASSPATH="$WRAPPER_JAR"

# Escape CLASSPATH for MSYS if necessary
if $msys ; then
    CLASSPATH=$(echo "$CLASSPATH" | sed 's&/c/\([a-zA-Z]\)&\1:S')
fi

# Split up the JVM options only if DEFAULT_JVM_OPTS is specified.
JVM_OPTS_ARRAY=
if [ -n "$DEFAULT_JVM_OPTS" ]; then
    # Based on http://stackoverflow.com/a/13598773/1438735
    # Temporarily disable path pattern matching.
    set -f
    # Uniformly split on spaces.
    # The explicit initializer prevents issues with $DEFAULT_JVM_OPTS starting with a dash.
    # shellcheck disable=SC2086
    DEFAULT_JVM_OPTS_ARRAY=($DEFAULT_JVM_OPTS)
    # Re-enable path pattern matching.
    set +f
    JVM_OPTS_ARRAY=()
    for PARAM in "${DEFAULT_JVM_OPTS_ARRAY[@]}"; do
        # Skip empty params.
        [ -z "$PARAM" ] && continue
        # Ensure an array element does not contain spaces.
        # Otherwise this would fail: DEFAULT_JVM_OPTS='-Dfoo="bar baz"'
        # TODO: This is not perfect, for example it does not preserve escaped quotes.
        case $PARAM in
            *\ *) JVM_OPTS_ARRAY+=("\"$PARAM\"");;
            *)    JVM_OPTS_ARRAY+=("$PARAM");;
        esac
    done
fi

# Collect all arguments for the java command, following the shell quoting and splitting rules.
#
# The following solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (' Backpack for Java' ' -Dfoo=bar ' '-Dfoo2="bar baz"' '' )
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# The following solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
# Following the shell quoting and splitting rules.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Collect all arguments for the java command.
#
# This solution is based on http://stackoverflow.com/a/13598773/1438735
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#

# Prepare the JVM options
#
# If DEFAULT_JVM_OPTS is specified, then it is used directly.
# Otherwise, JAVA_OPTS and GRADLE_OPTS are used, if defined.
#
# This allows a project to specify its own default JVM options, while still allowing
# users to override them with JAVA_OPTS and GRADLE_OPTS.
#
# Any DEFAULT_JVM_OPTS and JAVA_OPTS are passed as arrays to the JVM.
# Any GRADLE_OPTS are passed as a single string to the JVM.
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Prepare the JVM options
#
# If DEFAULT_JVM_OPTS is specified, then it is used directly.
# Otherwise, JAVA_OPTS and GRADLE_OPTS are used, if defined.
#
# This allows a project to specify its own default JVM options, while still allowing
# users to override them with JAVA_OPTS and GRADLE_OPTS.
#
# Any DEFAULT_JVM_OPTS and JAVA_OPTS are passed as arrays to the JVM.
# Any GRADLE_OPTS are passed as a single string to the JVM.
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Prepare the JVM options
#
# If DEFAULT_JVM_OPTS is specified, then it is used directly.
# Otherwise, JAVA_OPTS and GRADLE_OPTS are used, if defined.
#
# This allows a project to specify its own default JVM options, while still allowing
# users to override them with JAVA_OPTS and GRADLE_OPTS.
#
# Any DEFAULT_JVM_OPTS and JAVA_OPTS are passed as arrays to the JVM.
# Any GRADLE_OPTS are passed as a single string to the JVM.
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Prepare the JVM options
#
# If DEFAULT_JVM_OPTS is specified, then it is used directly.
# Otherwise, JAVA_OPTS and GRADLE_OPTS are used, if defined.
#
# This allows a project to specify its own default JVM options, while still allowing
# users to override them with JAVA_OPTS and GRADLE_OPTS.
#
# Any DEFAULT_JVM_OPTS and JAVA_OPTS are passed as arrays to the JVM.
# Any GRADLE_OPTS are passed as a single string to the JVM.
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
#
# Prepare the JVM options
#
# If DEFAULT_JVM_OPTS is specified, then it is used directly.
# Otherwise, JAVA_OPTS and GRADLE_OPTS are used, if defined.
#
# This allows a project to specify its own default JVM options, while still allowing
# users to override them with JAVA_OPTS and GRADLE_OPTS.
#
# Any DEFAULT_JVM_OPTS and JAVA_OPTS are passed as arrays to the JVM.
# Any GRADLE_OPTS are passed as a single string to the JVM.
#
# This is required to support all possible cases of JAVA_OPTS, GRADLE_OPTS and APP_OPTS.
#
# (Padded with spaces to avoid issues with $JAVA_OPTS starting with a dash)
#
# It also generates a warning when an option starts with a dash and does not have a value.
# This is a common mistake.
#
# TODO: Support options with escaped quotes.
# TODO: Support options with embedded new lines.
# TODO: Support options with other special characters.
#
# In addition, this solution also supports "DEFAULT_JVM_OPTS" which is a space separated string.
# All options from "DEFAULT_JVM_OPTS" are passed as a single argument to the JVM.
#
# It is not possible to use a single array for all options because the shell quoting and splitting rules are different for
# space separated strings and for arrays.
#
# For example, the following call:
#
#     DEFAULT_JVM_OPTS="-Dfoo='bar baz'" JAVA_OPTS=(-Dfoo2='"bar baz"') ./gradlew
#
# must result in the following options passed to the JVM:
#
#     "-Dfoo='bar baz'" "-Dfoo2=\"bar baz\""
#
# Simply concatenating the arrays does not work because the shell will split the DEFAULT_JVM_OPTS string on spaces.
#
# Also, this solution ensures that the classpath is passed as a single argument to the JVM.
#
# It also handles the case when JAVA_OPTS or GRADLE_OPTS is not set.
#
# Finally, it also handles the case when the classpath contains spaces.
# This is very important on Windows.
#
# Execute Gradle
exec "$JAVACMD" "${JVM_OPTS_ARRAY[@]}" "$JAVA_OPTS" "$GRADLE_OPTS" "-Dorg.gradle.appname=$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
