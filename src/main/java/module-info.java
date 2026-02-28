module com.textharvester {
    requires javafx.controls;
    requires org.jsoup;
    requires org.yaml.snakeyaml;
    requires org.slf4j;

    requires static lombok;

    exports com.textharvester;
    exports com.textharvester.ui;

    opens com.textharvester.config to org.yaml.snakeyaml;
}
