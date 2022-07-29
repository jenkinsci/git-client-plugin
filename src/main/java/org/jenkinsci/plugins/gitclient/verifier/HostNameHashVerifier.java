/*
 * Copyright (c) 2007-2008 Trilead AG (http://www.trilead.com)

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

a.) Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
b.) Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
c.) Neither the name of Trilead nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

Trilead SSH-2 for Java includes code that was written by Dr. Christian Plattner
during his PhD at ETH Zurich. The license states the following:

Copyright (c) 2005 - 2006 Swiss Federal Institute of Technology (ETH Zurich),
  Department of Computer Science (http://www.inf.ethz.ch),
  Christian Plattner. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

a.) Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
b.) Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
c.) Neither the name of ETH Zurich nor the names of its contributors may
    be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

The Java implementations of the AES, Blowfish and 3DES ciphers have been
taken (and slightly modified) from the cryptography package released by
"The Legion Of The Bouncy Castle".

Their license states the following:

Copyright (c) 2000 - 2004 The Legion Of The Bouncy Castle
(http://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */
package org.jenkinsci.plugins.gitclient.verifier;

import com.trilead.ssh2.crypto.Base64;
import com.trilead.ssh2.crypto.digest.MessageMac;
import com.trilead.ssh2.crypto.digest.SHA1;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

public class HostNameHashVerifier {

    private HostNameHashVerifier() {
        // to hide the implicit public constructor
    }

    public static boolean checkHashed(String entry, String hostname)
    {
        if (!entry.startsWith("|1|"))
            return false;

        int delim_idx = entry.indexOf('|', 3);

        if (delim_idx == -1)
            return false;

        String salt_base64 = entry.substring(3, delim_idx);
        String hash_base64 = entry.substring(delim_idx + 1);

        byte[] salt = null;
        byte[] hash = null;

        try
        {
            salt = Base64.decode(salt_base64.toCharArray());
            hash = Base64.decode(hash_base64.toCharArray());
        }
        catch (IOException e)
        {
            return false;
        }

        SHA1 sha1 = new SHA1();

        if (salt.length != sha1.getDigestLength())
            return false;

        byte[] dig = hmacSha1Hash(salt, hostname);

        for (int i = 0; i < dig.length; i++)
            if (dig[i] != hash[i])
                return false;

        return true;
    }

    private static byte[] hmacSha1Hash(byte[] salt, String hostname)
    {

        if (salt.length != 20) {
            throw new IllegalArgumentException("Salt has wrong length (" + salt.length + ")");
        }

        MessageMac messageMac = new MessageMac("hmac-sha1", salt);

        try {
            byte[] message = hostname.getBytes("ISO-8859-1");
            messageMac.update(message, 0, message.length);
        } catch (UnsupportedEncodingException ignore) {
            /* Actually, ISO-8859-1 is supported by all correct
             * Java implementations. But... you never know. */
            byte[] message = hostname.getBytes(Charset.defaultCharset());
            messageMac.update(message, 0, message.length);
        }

        byte[] dig = new byte[20];

        messageMac.getMac(dig, 0);

        return dig;
    }
}
