package sv.redent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import static sv.redent.Json.*;

final class Fixtures {
    static ObjectNode company(){return obj("nit","06140101011011","nrc","1234567","nombre","EMPRESA FICTICIA DE PRUEBA","nombreComercial","REDENT PRUEBA","codActividad","46493","descActividad","Venta de insumos dentales", "direccion",address(),"telefono","22223333","correo","pruebas@example.com","codEstableMH","M001","codPuntoVentaMH","P001","codEstable","M001","codPuntoVenta","P001","tipoEstablecimiento","01");}
    static ObjectNode address(){return obj("departamento","06","municipio","23","distrito","14","complemento","DIRECCIÓN FICTICIA DE PRUEBAS");}
    static ObjectNode client(){return obj("codigo","CLI-PRUEBA","nombre","CLIENTE FICTICIO","nit","06140202022022","nrc","7654321","tipoDocumento","36","numDocumento","06140202022022","codActividad","86203","descActividad","Servicios odontológicos","nombreComercial",null,"direccion",address(),"telefono","22224444","correo","cliente@example.com","codPais","9300","nombrePais","Guatemala","complemento","DIRECCIÓN FICTICIA EXTERIOR","tipoPersona",2);}
    static ObjectNode input(String type){return obj("tipo",type,"cliente",client(),"condicionOperacion",1,"formaPago","01","items",List.of(obj("codigo","P001","descripcion","Producto ficticio","cantidad",2,"precio",100,"descuento",10,"tipoItem",1,"uniMedida",59,"operacion","GRAVADA")),"exportacion",obj("tipoItemExpor",1,"recintoFiscal","01","tipoRegimen","EX","regimen","EX-1.1000.000","codIncoterms","01","descIncoterms","EXW"));}
}
