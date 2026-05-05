#!/bin/bash
# Setup script for building Dynmap in Claude.ai sandbox environment
# Run this once at the start of each session before ./gradlew build

set -e

echo "=== Setting up Gradle build environment for sandbox ==="

# 1. Parse proxy URL
if [ -z "$https_proxy" ]; then
    echo "ERROR: https_proxy not set. Are you in the sandbox?"
    exit 1
fi

proxy_url="$https_proxy"
host_port=$(echo "$proxy_url" | sed 's|http://.*@||')
PROXY_HOST=$(echo "$host_port" | cut -d: -f1)
PROXY_PORT=$(echo "$host_port" | cut -d: -f2)
auth_part=$(echo "$proxy_url" | sed 's|http://||' | sed 's|@.*||')
PROXY_USER=$(echo "$auth_part" | cut -d: -f1)
PROXY_PASS=$(echo "$auth_part" | cut -d: -f2-)

echo "Upstream proxy: $PROXY_HOST:$PROXY_PORT"

# 2. Create local auth proxy if not running
if ! pgrep -f "sandbox_auth_proxy.py" > /dev/null; then
    echo "Starting local auth proxy..."

    cat > /tmp/sandbox_auth_proxy.py << 'PYTHON'
#!/usr/bin/env python3
import socket, threading, base64, os, sys

proxy_url = os.environ.get('https_proxy', '')
if '@' not in proxy_url:
    print("No auth in proxy URL")
    sys.exit(1)

auth_part = proxy_url.split('//')[1].split('@')[0]
host_port = proxy_url.split('@')[1]
UPSTREAM_HOST = host_port.split(':')[0]
UPSTREAM_PORT = int(host_port.split(':')[1])
PROXY_USER = auth_part.split(':')[0]
PROXY_PASS = ':'.join(auth_part.split(':')[1:])
auth_b64 = base64.b64encode(f"{PROXY_USER}:{PROXY_PASS}".encode()).decode()
LOCAL_PORT = 3128

def handle_client(client_socket):
    try:
        request = b""
        while b"\r\n\r\n" not in request:
            chunk = client_socket.recv(4096)
            if not chunk: return
            request += chunk

        request_str = request.decode('utf-8', errors='replace')
        lines = request_str.split('\r\n')
        first_line = lines[0]

        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.connect((UPSTREAM_HOST, UPSTREAM_PORT))

        new_request = first_line + '\r\n'
        new_request += f'Proxy-Authorization: Basic {auth_b64}\r\n'
        for line in lines[1:]:
            if line.lower().startswith('proxy-authorization'): continue
            new_request += line + '\r\n'
            if line == '': break
        upstream.sendall(new_request.encode())

        def forward(src, dst):
            try:
                while True:
                    data = src.recv(65536)
                    if not data: break
                    dst.sendall(data)
            except: pass
            finally:
                try: dst.shutdown(socket.SHUT_WR)
                except: pass

        t1 = threading.Thread(target=forward, args=(client_socket, upstream), daemon=True)
        t2 = threading.Thread(target=forward, args=(upstream, client_socket), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
    finally:
        try: client_socket.close()
        except: pass

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', LOCAL_PORT))
    server.listen(100)
    print(f"Auth proxy listening on 127.0.0.1:{LOCAL_PORT}")
    sys.stdout.flush()
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle_client, args=(client,), daemon=True).start()

if __name__ == '__main__':
    main()
PYTHON

    python3 /tmp/sandbox_auth_proxy.py > /tmp/proxy.log 2>&1 &
    sleep 2

    if pgrep -f "sandbox_auth_proxy.py" > /dev/null; then
        echo "✓ Local auth proxy started on port 3128"
    else
        echo "ERROR: Failed to start proxy"
        cat /tmp/proxy.log
        exit 1
    fi
else
    echo "✓ Local auth proxy already running"
fi

# 3. Configure Gradle to use local proxy
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties << 'EOF'
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=3128
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=3128
systemProp.http.nonProxyHosts=localhost|127.0.0.1
EOF
echo "✓ Gradle proxy configured"

# 4. Download Gradle wrapper if needed
GRADLE_VERSION="8.12"
GRADLE_DIST_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"

if [ ! -d "$GRADLE_DIST_DIR" ] || [ -z "$(find "$GRADLE_DIST_DIR" -name "gradle-${GRADLE_VERSION}" -type d 2>/dev/null)" ]; then
    echo "Downloading Gradle ${GRADLE_VERSION}..."
    curl -L -o /tmp/gradle-${GRADLE_VERSION}-bin.zip \
        "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"

    # Trigger wrapper to create hash directory
    ./gradlew --version 2>/dev/null || true

    hash_dir=$(find "$GRADLE_DIST_DIR" -maxdepth 1 -type d ! -name "gradle-${GRADLE_VERSION}-bin" 2>/dev/null | head -1)
    if [ -n "$hash_dir" ]; then
        cp /tmp/gradle-${GRADLE_VERSION}-bin.zip "$hash_dir/"
        cd "$hash_dir"
        unzip -q -o gradle-${GRADLE_VERSION}-bin.zip
        touch gradle-${GRADLE_VERSION}-bin.zip.ok
        cd - > /dev/null
        echo "✓ Gradle ${GRADLE_VERSION} installed"
    fi
else
    echo "✓ Gradle ${GRADLE_VERSION} already cached"
fi

# 5. Build s3-lite if not in local Maven
if [ ! -d "$HOME/.m2/repository/io/github/linktosriram/s3lite/core" ]; then
    echo "Building s3-lite from source..."
    cd /tmp
    if [ ! -d "s3-lite" ]; then
        git clone https://github.com/linktosriram/s3-lite.git
    fi
    cd s3-lite

    # Update to compatible Gradle version
    cat > gradle/wrapper/gradle-wrapper.properties << EOF
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

    # Update build.gradle for maven-publish
    cat > build.gradle << 'GRADLE'
allprojects {
    apply plugin: 'java-library'
    apply plugin: 'maven-publish'
    group = "io.github.linktosriram.s3lite"
    version = "0.0.2-SNAPSHOT"
    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    publishing {
        publications {
            maven(MavenPublication) { from components.java }
        }
    }
    repositories { mavenCentral() }
    test { useJUnitPlatform() }
    dependencies {
        testImplementation 'org.junit.jupiter:junit-jupiter-api:5.9.1'
        testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.9.1'
    }
}
project(':api') { dependencies { api project(':http-client-spi'); api 'jakarta.xml.bind:jakarta.xml.bind-api:3.0.1' } }
project(':core') { dependencies { api project(':api'); implementation project(':util') } }
project(':http-client-apache') { dependencies { api project(':http-client-spi'); api 'org.apache.httpcomponents:httpclient:4.5.13' } }
project(':http-client-url-connection') { dependencies { api project(':http-client-spi') } }
GRADLE

    ./gradlew build publishToMavenLocal -x test
    cd - > /dev/null
    echo "✓ s3-lite installed to local Maven"
else
    echo "✓ s3-lite already in local Maven"
fi

echo ""
echo "=== Setup complete! ==="
echo "You can now run: ./gradlew setup build"
