/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// ---------------------------------------------------------------------------
//  Includes
// ---------------------------------------------------------------------------
#include <xercesc/framework/StdInInputSource.hpp>
#include <xercesc/parsers/SAXParser.hpp>
#include "srcml2token.hpp"
#include <xercesc/util/OutOfMemoryException.hpp>
#include <sstream>
#include <string>

// ---------------------------------------------------------------------------
//  Local helper methods
// ---------------------------------------------------------------------------
void usage()
{
    XERCES_STD_QUALIFIER cout << "\nUsage:\n"
            "    srcml2token  <XML file>\n"
            "    srcml2token  --server\n\n"
            "This program converts the output of srcML into a simplified tokenized version\n"
            "--server reads `PARSE <path>` lines on stdin and answers each with the tokens,\n"
            "then `\\x01ERR <diagnostic>` lines and a final `\\x01END <status>` line.\n"
         << XERCES_STD_QUALIFIER endl;
}

static int parseOne(SAX2XMLReader* parser, const char* path)
{
    srcml2tokenResetState();
    srcml2tokenHandlers handler;
    parser->setContentHandler(&handler);
    parser->setErrorHandler(&handler);
    try
    {
        if (path == NULL) {
            StdInInputSource src;
            parser->parse(src);
        } else {
            parser->parse(path);
        }
    }
    catch (const OutOfMemoryException&)
    {
        *srcml2tokenDiag << "OutOfMemoryException" << XERCES_STD_QUALIFIER endl;
        return 4;
    }
    catch (const XMLException& e)
    {
        *srcml2tokenDiag << "\nError during parsing: \n"
             << StrX(e.getMessage())
             << "\n" << XERCES_STD_QUALIFIER endl;
        return 4;
    }
    catch (...)
    {
        *srcml2tokenDiag << "Unexpected exception during parsing" << XERCES_STD_QUALIFIER endl;
        return 4;
    }
    return 0;
}

static int serverLoop(SAX2XMLReader* parser)
{
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.compare(0, 6, "PARSE ") != 0) {
            std::cout << "\x01" "ERR malformed request [" << line << "]\n" "\x01" "END 2\n" << std::flush;
            continue;
        }
        std::ostringstream diag;
        srcml2tokenDiag = &diag;
        int status = parseOne(parser, line.c_str() + 6);
        srcml2tokenDiag = &std::cerr;

        std::istringstream lines(diag.str());
        std::string d;
        while (std::getline(lines, d)) {
            std::cout << "\x01" "ERR " << d << "\n";
        }
        std::cout << "\x01" "END " << status << "\n" << std::flush;
    }
    return 0;
}

// ---------------------------------------------------------------------------
//  Program entry point
// ---------------------------------------------------------------------------
int main(int argC, char* argV[])
{
    // Initialize the XML4C system
    try
    {
         XMLPlatformUtils::Initialize();
    }

    catch (const XMLException& toCatch)
    {
         XERCES_STD_QUALIFIER cerr << "Error during initialization! Message:\n"
              << StrX(toCatch.getMessage()) << XERCES_STD_QUALIFIER endl;
         return 1;
    }


    SAX2XMLReader* parser = XMLReaderFactory::createXMLReader();

    int status;
    if (argC >= 2 && strcmp(argV[1], "--server") == 0) {
        status = serverLoop(parser);
    } else {
        status = parseOne(parser, argC < 2 ? NULL : argV[1]);
    }

    //
    //  Delete the parser itself.  Must be done prior to calling Terminate, below.
    //
    delete parser;

    XMLPlatformUtils::Terminate();

    return status;
}
