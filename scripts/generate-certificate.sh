#!/bin/bash

# Set variables
CERT_DIR="certificates"
CERT_NAME="server"
DAYS_VALID=365
COUNTRY="US"
STATE="State"
LOCALITY="City"
ORGANIZATION="Organization Name"
ORGANIZATIONAL_UNIT="IT"
COMMON_NAME="localhost"

# Prompt user for password
read -sp "Create a password for the certificate: " PASSWORD
echo

# Create certificates directory if it doesn't exist
mkdir -p $CERT_DIR

# Generate private key and certificate
openssl req -x509 \
    -newkey rsa:2048 \
    -keyout "$CERT_DIR/$CERT_NAME.key" \
    -out "$CERT_DIR/$CERT_NAME.crt" \
    -days $DAYS_VALID \
    -nodes \
    -subj "/C=$COUNTRY/ST=$STATE/L=$LOCALITY/O=$ORGANIZATION/OU=$ORGANIZATIONAL_UNIT/CN=$COMMON_NAME"

# Generate PKCS12 format with a compatible key size
openssl pkcs12 -export \
    -in "$CERT_DIR/$CERT_NAME.crt" \
    -inkey "$CERT_DIR/$CERT_NAME.key" \
    -out "$CERT_DIR/$CERT_NAME.p12" \
    -name "$CERT_NAME" \
    -password pass:$PASSWORD \
    -legacy

echo "Certificate generation complete!"
echo "Files created:"
echo "  - $CERT_DIR/$CERT_NAME.key (Private key)"
echo "  - $CERT_DIR/$CERT_NAME.crt (Certificate)"
echo "  - $CERT_DIR/$CERT_NAME.p12 (PKCS12 format - Android)"
