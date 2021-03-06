The Asterix ADM Parser
======================

The ADM parser inside Asterix is composed by two different components:

* **The Parser** AdmTupleParser, which converts the adm tokens in internal objects
* **The Lexer**  AdmLexer, which scans the adm file and returns a list of adm tokens

These two classes belong to the package:

    edu.uci.ics.asterix.runtime.operators.file

The Parser is loaded through a factory (*AdmSchemafullRecordParserFactory*) by

    edu.uci.ics.asterix.external.dataset.adapter.FileSystemBasedAdapter extends AbstractDatasourceAdapter


How to add a new datatype
-------------------------
The ADM format allows two different kinds of datatype:

* primitive
* with constructor

A primitive datatype allows to write the actual value of the field without extra markup:

    { name : "Diego", age : 23 }

while the datatypes with constructor require to specify first the type of the value and then a string with the serialized value

    { center : point3d("P2.1,3,8.5") }

In order to add a new datatype the steps are:

1.  Add the new token to the **Lexer**
  * **if the datatype is primite** is necessary to create a TOKEN able to recognize **the format of the value**
  * **if the datatype is with constructor** is necessary to create **only** a TOKEN able to recognize **the name of the constructor**

2.  Change the **Parser** in order to convert correctly the new token in internal objects
  * This will require to **add new cases to the switch-case statements** and the introduction of **a serializer/deserializer object** for that datatype.


The Lexer
----------
To add new datatype or change the tokens definition you have to change ONLY the file adm.grammar located in 
	asterix-runtime/src/main/resources/adm.grammar
The lexer will be generated from that definition file during each maven building.

The maven configuration in located in asterix-runtime/pom.xml


> Author: Diego Giorgini - diegogiorgini@gmail.com   
> 6 December 2012
