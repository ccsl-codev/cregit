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

#include <dlfcn.h>
#include <srcml.h>
#include <xercesc/framework/MemBufInputSource.hpp>
#include <xercesc/util/OutOfMemoryException.hpp>
#include "srcml2token.hpp"

static int usage()
{
    XERCES_STD_QUALIFIER cerr << "Usage: srcml2token -l <C|C++|Java> <source file>\n"
            "       srcml2token --libsrcml-path\n"
            "Parses the file with libsrcml and prints its tokens.\n";
    return 2;
}

static int fail(const std::string& why)
{
    XERCES_STD_QUALIFIER cerr << "srcml2token: " << why << XERCES_STD_QUALIFIER endl;
    return 3;
}

// The tokenizer identity digests the library that is loaded, not the one on disk.
static int print_libsrcml_path()
{
    Dl_info info;
    if (!dladdr(reinterpret_cast<void*>(&srcml_version_string), &info) || !info.dli_fname)
        return fail("cannot find the loaded libsrcml");
    XERCES_STD_QUALIFIER cout << info.dli_fname << XERCES_STD_QUALIFIER endl;
    return 0;
}

// The same calls as `srcml -l <language> --position <file>`. Like that command,
// refuse an extension that srcML does not know: it would ignore the language.
static int parse_source(const char* language, const char* filename, std::string& xml)
{
    srcml_archive* archive = srcml_archive_create();
    char* buffer = nullptr;
    size_t size = 0;
    int status = srcml_archive_write_open_memory(archive, &buffer, &size);
    if (status == SRCML_STATUS_OK) status = srcml_archive_enable_option(archive, SRCML_OPTION_POSITION);
    if (status == SRCML_STATUS_OK) status = srcml_archive_set_language(archive, language);
    if (status == SRCML_STATUS_OK) status = srcml_archive_set_tabstop(archive, 8);
    if (status == SRCML_STATUS_OK) status = srcml_archive_enable_solitary_unit(archive);
    if (status == SRCML_STATUS_OK) status = srcml_archive_disable_hash(archive);
    if (status == SRCML_STATUS_OK && !srcml_archive_check_extension(archive, filename))
        status = SRCML_STATUS_UNSET_LANGUAGE;

    srcml_unit* unit = status == SRCML_STATUS_OK ? srcml_unit_create(archive) : nullptr;
    if (unit) {
        status = srcml_unit_set_language(unit, language);
        if (status == SRCML_STATUS_OK) status = srcml_unit_set_filename(unit, filename);
        if (status == SRCML_STATUS_OK) status = srcml_unit_parse_filename(unit, filename);
        if (status == SRCML_STATUS_OK) status = srcml_archive_write_unit(archive, unit);
        srcml_unit_free(unit);
    }
    srcml_archive_close(archive);
    srcml_archive_free(archive);
    if (status == SRCML_STATUS_OK && buffer)
        xml.assign(buffer, size);
    srcml_memory_free(buffer);
    return status;
}

static int tokenize(const std::string& xml)
{
    XMLPlatformUtils::Initialize();
    SAX2XMLReader* parser = XMLReaderFactory::createXMLReader();
    srcml2tokenHandlers handler;
    parser->setContentHandler(&handler);
    parser->setErrorHandler(&handler);
    int exitCode = 0;
    try {
        MemBufInputSource source(reinterpret_cast<const XMLByte*>(xml.data()), xml.size(), "srcml");
        parser->parse(source);
    }
    catch (const OutOfMemoryException&) {
        XERCES_STD_QUALIFIER cerr << "OutOfMemoryException" << XERCES_STD_QUALIFIER endl;
        exitCode = 4;
    }
    catch (const XMLException& e) {
        XERCES_STD_QUALIFIER cerr << "\nError during parsing: \n" << StrX(e.getMessage()) << "\n" << XERCES_STD_QUALIFIER endl;
        exitCode = 4;
    }
    delete parser;
    XMLPlatformUtils::Terminate();
    return exitCode;
}

int main(int argC, char* argV[])
{
    if (argC == 2 && std::string(argV[1]) == "--libsrcml-path")
        return print_libsrcml_path();
    if (argC != 4 || std::string(argV[1]) != "-l")
        return usage();

    std::string xml;
    int status = parse_source(argV[2], argV[3], xml);
    if (status == SRCML_STATUS_UNSET_LANGUAGE)
        return fail("srcML does not know the extension of [" + std::string(argV[3]) + "]");
    if (status != SRCML_STATUS_OK)
        return fail("libsrcml could not parse [" + std::string(argV[3]) + "] as " + argV[2]
                    + " (status " + std::to_string(status) + ")");
    return tokenize(xml);
}
