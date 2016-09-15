import groovy.transform.CompileStatic

@CompileStatic
class ApiExtension {
   final Set<String> exports = []

   void exports(String pkg) { exports << pkg }

}
