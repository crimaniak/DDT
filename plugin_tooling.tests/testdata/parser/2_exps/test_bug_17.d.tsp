Ⓗ▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂
http://code.google.com/p/ddt/issues/detail?id=17
▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂
#PARSE(EXPRESSION)   ceil(num3: 0)
#STRUCTURE_EXPECTED: ExpCall( #@ExpIdentifier ExpNamedArgument(Integer) )
▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂
#PARSE(EXPRESSION)   cast(int) ceil(num3: 0)
#STRUCTURE_EXPECTED: ExpCast( RefPrimitive ExpCall( #@ExpIdentifier ExpNamedArgument(Integer) ) )
▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂
#PARSE(EXPRESSION) #@IGNORE_ALL_CHECKS
showanims ? cast(int) ceil(num3 : 0 )
▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂
#PARSE(EXPRESSION) #@IGNORE_ALL_CHECKS
showanims ? cast(int) ceil(cast(float)num2 / cast(float) num3 : 0)